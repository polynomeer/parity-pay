package io.parity.pay.payment.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.payment.application.event.PaymentEvents;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.PaymentQuery;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.idempotency.IdempotencyRecord;
import io.parity.pay.shared.idempotency.IdempotencyStatus;
import io.parity.pay.shared.idempotency.IdempotencyStore;
import io.parity.pay.shared.idempotency.RequestHasher;
import io.parity.pay.wallet.application.port.in.WalletFundsUseCase;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 페이머니 결제 승인.
 *
 * <p>외부기관 호출이 없으므로 다음이 하나의 PostgreSQL 트랜잭션에서 처리됩니다.
 *
 * <ol>
 *   <li>멱등성 레코드 선점
 *   <li>지갑 상태·소유권 확인
 *   <li>조건부 잔액 차감 (INV-003)
 *   <li>결제 분개 전기 JE-003 (INV-001)
 *   <li>결제 상태 확정과 멱등 응답 저장
 * </ol>
 *
 * <p>어느 단계든 실패하면 모두 롤백됩니다. 근거: docs/05-technical-design.md §7
 */
@Service
public class PaymentService implements ApprovePaymentUseCase, PaymentQuery {

    static final String OPERATION = "PAYMENT";

    private final PaymentRepository paymentRepository;
    private final WalletFundsUseCase walletFunds;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final IdempotencyStore idempotencyStore;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    public PaymentService(
            PaymentRepository paymentRepository,
            WalletFundsUseCase walletFunds,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            IdempotencyStore idempotencyStore,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.walletFunds = walletFunds;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.idempotencyStore = idempotencyStore;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentView approve(ApprovePaymentCommand command) {
        String requestHash = canonicalHash(command);
        IdempotencyRecord record = idempotencyStore.beginOrGet(
                command.memberId().value(), OPERATION, command.idempotencyKey(), requestHash);

        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "the same Idempotency-Key was used with a different request body");
        }

        Optional<Payment> existing =
                record.businessReference().map(PaymentId::of).flatMap(paymentRepository::findById);
        if (existing.isPresent()) {
            // 같은 키의 재요청입니다. 금액을 다시 움직이지 않고 기존 결과를 돌려줍니다. 근거: INV-004
            return PaymentView.of(existing.get());
        }

        // 같은 주문에 이미 승인된 결제가 있으면 새로 승인하지 않습니다.
        // 근거: docs/04-payment-policy.md §4, docs/06-domain-state-design.md §6
        paymentRepository.findActiveByOrderId(command.orderId()).ifPresent(active -> {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "order already has an approved payment");
        });

        WalletFundsUseCase.SpendableWallet wallet = walletFunds.requireSpendable(
                command.walletId(), command.memberId(), command.amount().currency());

        Payment payment = Payment.request(
                        command.orderId(),
                        command.memberId(),
                        wallet.walletId(),
                        command.merchantId(),
                        command.amount(),
                        command.method(),
                        command.idempotencyKey(),
                        clock.instant())
                .process(clock.instant());

        // 잔액이 부족하면 여기서 확정 실패하고 트랜잭션 전체가 롤백됩니다.
        // 실패한 시도를 별도 행으로 남기는 것은 외부 PG 결제를 도입하는 Phase 4에서 함께 다룹니다.
        walletFunds.debit(payment.walletId(), payment.requestedAmount());

        Payment approved = payment.approve(clock.instant());

        LedgerAccount userPayMoney = resolveLedgerAccount.resolve(
                AccountCode.USER_PAY_MONEY,
                approved.walletId().value(),
                approved.approvedAmount().currency());
        LedgerAccount merchantPayable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_PAYABLE,
                approved.merchantId().value(),
                approved.approvedAmount().currency());

        LedgerTransaction ledgerTransaction = postJournal.post(JournalFactory.paymentApproved(
                approved.id(),
                userPayMoney.id(),
                merchantPayable.id(),
                approved.approvedAmount(),
                approved.approvedAt()));

        Payment saved = paymentRepository.save(approved);
        outboxAppender.append(PaymentEvents.paymentApproved(saved, ledgerTransaction.id()));

        idempotencyStore.settle(
                command.memberId().value(),
                OPERATION,
                command.idempotencyKey(),
                IdempotencyStatus.COMPLETED,
                saved.id().value());

        return PaymentView.of(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView getPayment(MemberId memberId, PaymentId paymentId) {
        Payment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
        payment.requireOwnedBy(memberId);
        return PaymentView.of(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView getPaymentByOrderId(MemberId memberId, String orderId) {
        Payment payment = paymentRepository
                .findActiveByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
        payment.requireOwnedBy(memberId);
        return PaymentView.of(payment);
    }

    private static String canonicalHash(ApprovePaymentCommand command) {
        String canonical = String.join(
                "|",
                command.memberId().toString(),
                command.orderId(),
                command.walletId().toString(),
                command.merchantId().toString(),
                Long.toString(command.amount().amount()),
                command.amount().currency().name(),
                command.method().name());
        return RequestHasher.sha256(canonical);
    }
}
