package io.parity.pay.payment.application.service;

import io.parity.pay.ledger.application.port.in.LedgerBalanceQuery;
import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.payment.application.event.PaymentEvents;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.out.PaymentCancellationRepository;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.application.port.out.PgRefundPort;
import io.parity.pay.payment.application.port.out.PgRefundPort.PgRefundResult;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.idempotency.IdempotencyRecord;
import io.parity.pay.shared.idempotency.IdempotencyStatus;
import io.parity.pay.shared.idempotency.IdempotencyStore;
import io.parity.pay.shared.idempotency.RequestHasher;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.port.in.WalletFundsUseCase;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 취소.
 *
 * <p>취소는 원결제를 참조하는 독립 Aggregate이며, 원장 원문을 수정하지 않고 상쇄 분개(JE-004)를
 * 새로 만듭니다. 근거: ADR-009
 *
 * <p>동시 취소 초과는 저장소의 조건부 UPDATE로 막습니다. 예약(processing) → 확정(completed)의 두
 * 단계를 두는 이유는, 외부 취소가 들어오는 Phase 4에서 예약과 확정 사이에 외부 호출이 끼기 때문입니다.
 * 페이머니 취소는 외부 호출이 없어 두 단계가 같은 트랜잭션 안에서 연달아 일어납니다.
 */
@Service
public class PaymentCancellationService implements CancelPaymentUseCase {

    static final String OPERATION = "PAYMENT_CANCELLATION";

    private final PaymentRepository paymentRepository;
    private static final Logger log = LoggerFactory.getLogger(PaymentCancellationService.class);

    private final PaymentCancellationRepository cancellationRepository;
    private final CancellationTransactions cancellationTransactions;
    private final PgRefundPort pgRefundPort;
    private final WalletFundsUseCase walletFunds;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final LedgerBalanceQuery ledgerBalanceQuery;
    private final IdempotencyStore idempotencyStore;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    public PaymentCancellationService(
            PaymentRepository paymentRepository,
            PaymentCancellationRepository cancellationRepository,
            CancellationTransactions cancellationTransactions,
            PgRefundPort pgRefundPort,
            WalletFundsUseCase walletFunds,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            LedgerBalanceQuery ledgerBalanceQuery,
            IdempotencyStore idempotencyStore,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.cancellationRepository = cancellationRepository;
        this.cancellationTransactions = cancellationTransactions;
        this.pgRefundPort = pgRefundPort;
        this.walletFunds = walletFunds;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.ledgerBalanceQuery = ledgerBalanceQuery;
        this.idempotencyStore = idempotencyStore;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CancellationView cancel(CancelPaymentCommand command) {
        String requestHash = canonicalHash(command);
        IdempotencyRecord record = idempotencyStore.beginOrGet(
                command.memberId().value(), OPERATION, command.idempotencyKey(), requestHash);

        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "the same Idempotency-Key was used with a different request body");
        }

        Optional<PaymentCancellation> existing =
                record.businessReference().map(CancellationId::of).flatMap(cancellationRepository::findById);
        if (existing.isPresent()) {
            return CancellationView.of(existing.get(), canceledAmountOf(command.paymentId()));
        }

        Payment payment = loadPayment(command.paymentId());
        payment.requireOwnedBy(command.memberId());
        payment.approvedAmount().requireSameCurrency(command.amount());

        // 환불은 결제한 곳으로 돌아갑니다. 카드로 받았으면 카드로 돌려주므로 아래 페이머니
        // 역분개 경로를 쓰면 안 됩니다. 근거: docs/04-payment-policy.md §6, JE-014
        if (payment.method().callsExternalProvider()) {
            return refundThroughProvider(command, payment);
        }

        // 1. 취소 가능액 예약. 여러 요청이 동시에 와도 합계가 승인액을 넘지 않습니다 (INV-005).
        if (paymentRepository.reserveCancellation(payment.id(), command.amount()) != 1) {
            throw cancellationRejected(payment);
        }

        PaymentCancellation cancellation = PaymentCancellation.request(
                        payment.id(), command.amount(), command.reason(), command.idempotencyKey(), clock.instant())
                .begin();
        cancellationRepository.save(cancellation);

        // 2. 예약을 확정 취소액으로 옮깁니다. 누적액이 승인액과 같아지면 결제는 CANCELED가 됩니다.
        if (paymentRepository.completeCancellation(payment.id(), command.amount()) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "reserved cancellation amount disappeared");
        }

        // 3. 상쇄 분개를 만들고, 지갑에 되돌리는 것은 트랜잭션의 마지막에 합니다.
        LedgerAccount userPayMoney = resolveLedgerAccount.resolve(
                AccountCode.USER_PAY_MONEY,
                payment.walletId().value(),
                command.amount().currency());
        LedgerAccount merchantPayable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_PAYABLE,
                payment.merchantId().value(),
                command.amount().currency());
        LedgerAccount merchantReceivable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_RECEIVABLE,
                payment.merchantId().value(),
                command.amount().currency());

        // 이미 정산이 지급되어 지급예정금이 남아 있지 않으면 부족분을 판매자 미수금으로 기록합니다.
        // 계정의 정상 잔액 방향을 어기지 않으면서 회수해야 할 채권을 드러냅니다.
        // 근거: docs/07-ledger-journal-catalog.md JE-009, docs/04-payment-policy.md §6
        Money payableBalance = ledgerBalanceQuery.balanceOf(merchantPayable.id());
        Money fromPayable = payableBalance.isGreaterThanOrEqualTo(command.amount()) ? command.amount() : payableBalance;
        Money fromReceivable = command.amount().minus(fromPayable);

        PaymentCancellation completed = cancellation.complete(clock.instant());
        LedgerTransaction ledgerTransaction = postJournal.post(JournalFactory.paymentCanceled(
                completed.id(),
                merchantPayable.id(),
                merchantReceivable.id(),
                userPayMoney.id(),
                fromPayable,
                fromReceivable,
                completed.completedAt()));

        cancellationRepository.save(completed);
        outboxAppender.append(PaymentEvents.cancellationCompleted(
                completed, payment.walletId(), payment.merchantId(), ledgerTransaction.id()));

        idempotencyStore.settle(
                command.memberId().value(),
                OPERATION,
                command.idempotencyKey(),
                IdempotencyStatus.COMPLETED,
                completed.id().value());

        // 잔액 복원을 마지막에 둡니다. 이 UPDATE가 지갑 행을 잠그고 잠금은 커밋까지 풀리지
        // 않으므로, 앞에 두면 계정 해석·잔액 조회·분개 전기·Outbox 기록이 전부 잠금 안에서
        // 일어납니다. 결제 승인에서 같은 이유로 같은 조치를 했습니다(M-007, M-009).
        //
        // 이 취소가 실패하면 트랜잭션 전체가 롤백되므로 순서를 바꿔도 결과는 같습니다.
        walletFunds.credit(payment.walletId(), command.amount());

        return CancellationView.of(completed, canceledAmountOf(payment.id()));
    }

    /**
     * 외부 PG 환불.
     *
     * <p>예약을 먼저 커밋하고, 외부 호출을 트랜잭션 밖에서 하고, 결과를 따로 확정합니다. 예외를
     * 실패로 단정하지 않습니다 — 환불은 이미 나갔을 수 있습니다. 근거: ADR-007
     */
    private CancellationView refundThroughProvider(CancelPaymentCommand command, Payment payment) {
        PaymentCancellation cancellation = cancellationTransactions.begin(command, payment);

        PgRefundResult result;
        try {
            result = pgRefundPort.refund(cancellation.id(), payment.id(), command.amount());
        } catch (RuntimeException e) {
            log.warn("pg refund outcome is unknown for cancellation {}", cancellation.id(), e);
            result = PgRefundResult.unknown(null);
        }

        PaymentCancellation settled =
                switch (result.outcome()) {
                    case REFUNDED -> cancellationTransactions.completeRefunded(
                            command.memberId(), cancellation, payment, result.externalReferenceId());
                    case DECLINED -> cancellationTransactions.completeDeclined(
                            command.memberId(), cancellation, payment, result.failureReason());
                    case UNKNOWN -> cancellationTransactions.markUnknown(
                            command.memberId(), cancellation, result.externalReferenceId());
                };
        return CancellationView.of(settled, canceledAmountOf(payment.id()));
    }

    /**
     * 예약이 실패한 이유를 구분합니다. 상태 때문인지 금액 때문인지에 따라 사용자가 할 수 있는 행동이
     * 다릅니다. 근거: docs/04-payment-policy.md §10
     */
    private BusinessException cancellationRejected(Payment payment) {
        Payment current = loadPayment(payment.id());
        if (!current.status().isCancellable()) {
            return new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "payment cannot be canceled in status " + current.status());
        }
        return new BusinessException(
                ErrorCode.CANCELLATION_AMOUNT_EXCEEDED, "cancellation amount exceeds the remaining cancellable amount");
    }

    private Payment loadPayment(PaymentId paymentId) {
        return paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
    }

    private Money canceledAmountOf(PaymentId paymentId) {
        return loadPayment(paymentId).completedCancellationAmount();
    }

    private static String canonicalHash(CancelPaymentCommand command) {
        String canonical = String.join(
                "|",
                command.memberId().toString(),
                command.paymentId().toString(),
                Long.toString(command.amount().amount()),
                command.amount().currency().name(),
                command.reason() == null ? "" : command.reason());
        return RequestHasher.sha256(canonical);
    }
}
