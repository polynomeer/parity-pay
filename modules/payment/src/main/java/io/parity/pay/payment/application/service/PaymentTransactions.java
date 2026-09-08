package io.parity.pay.payment.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.payment.application.event.PaymentEvents;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.application.port.out.PgApprovalPort;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.idempotency.IdempotencyRecord;
import io.parity.pay.shared.idempotency.IdempotencyStatus;
import io.parity.pay.shared.idempotency.IdempotencyStore;
import io.parity.pay.wallet.application.port.in.WalletFundsUseCase;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 PG 결제의 트랜잭션 경계.
 *
 * <p>페이머니 결제는 외부 호출이 없어 한 트랜잭션으로 끝나지만, PG 결제는 그럴 수 없습니다.
 * 외부 호출을 트랜잭션 안에서 하면 커넥션을 외부 응답만큼 붙잡고, 롤백해도 외부에서 일어난 일은
 * 되돌아가지 않습니다. 그래서 셋으로 나눕니다.
 *
 * <ol>
 *   <li>{@link #begin} — 멱등 키를 선점하고 결제를 PROCESSING으로 커밋합니다. 이 커밋이 있어야
 *       프로세스가 죽어도 "요청했다"는 사실이 남고 복구가 찾아낼 수 있습니다.
 *   <li>외부 호출 — 어떤 트랜잭션에도 속하지 않습니다.
 *   <li>{@code complete*} — 결과를 확정합니다. 승인이면 원장·이벤트가 같은 트랜잭션에 들어갑니다.
 * </ol>
 *
 * <p>근거: docs/05-technical-design.md §7, docs/09-consistency-recovery.md §7, ADR-007
 */
@Component
class PaymentTransactions {

    private final PaymentRepository paymentRepository;
    private final PgApprovalPort pgApprovalPort;
    private final WalletFundsUseCase walletFunds;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final IdempotencyStore idempotencyStore;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    PaymentTransactions(
            PaymentRepository paymentRepository,
            PgApprovalPort pgApprovalPort,
            WalletFundsUseCase walletFunds,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            IdempotencyStore idempotencyStore,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.pgApprovalPort = pgApprovalPort;
        this.walletFunds = walletFunds;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.idempotencyStore = idempotencyStore;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    /**
     * 페이머니 결제 승인.
     *
     * <p>외부 호출이 없으므로 멱등 선점·잔액 차감·분개 전기·상태 확정이 하나의 트랜잭션입니다.
     * 어느 단계든 실패하면 모두 롤백됩니다.
     *
     * <p>서비스가 아니라 이 빈에 있는 이유는 프록시입니다. 같은 빈 안에서 부르면 트랜잭션이 열리지
     * 않습니다. 근거: docs/05-technical-design.md §7
     */
    @Transactional
    Payment approveWithPayMoney(ApprovePaymentCommand command, String requestHash) {
        IdempotencyRecord record = idempotencyStore.beginOrGet(
                command.memberId().value(), PaymentService.OPERATION, command.idempotencyKey(), requestHash);

        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "the same Idempotency-Key was used with a different request body");
        }

        Optional<Payment> existing =
                record.businessReference().map(PaymentId::of).flatMap(paymentRepository::findById);
        if (existing.isPresent()) {
            // 같은 키의 재요청입니다. 금액을 다시 움직이지 않고 기존 결과를 돌려줍니다. 근거: INV-004
            return existing.get();
        }

        // 같은 주문에 이미 승인된 결제가 있으면 새로 승인하지 않습니다.
        // 근거: docs/04-payment-policy.md §4, docs/06-domain-state-design.md §6
        paymentRepository.findActiveByOrderId(command.orderId()).ifPresent(active -> {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "order already has an approved payment");
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
                PaymentService.OPERATION,
                command.idempotencyKey(),
                IdempotencyStatus.COMPLETED,
                saved.id().value());

        return saved;
    }

    /**
     * 멱등 키를 선점하고 결제를 PROCESSING으로 만듭니다.
     *
     * @return 이번에 새로 시작한 결제면 {@link Started#isNew()}가 true입니다. 이미 진행·완료된
     *     요청이면 기존 결제를 그대로 돌려줍니다.
     */
    @Transactional
    Started begin(ApprovePaymentCommand command, String requestHash) {
        IdempotencyRecord record = idempotencyStore.beginOrGet(
                command.memberId().value(), PaymentService.OPERATION, command.idempotencyKey(), requestHash);
        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "the same Idempotency-Key was used with a different request body");
        }

        Optional<Payment> existing =
                record.businessReference().map(PaymentId::of).flatMap(paymentRepository::findById);
        if (existing.isPresent()) {
            return new Started(existing.get(), false);
        }

        paymentRepository.findActiveByOrderId(command.orderId()).ifPresent(active -> {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "order already has an approved payment");
        });

        Payment payment = Payment.request(
                        command.orderId(),
                        command.memberId(),
                        command.walletId(),
                        command.merchantId(),
                        command.amount(),
                        command.method(),
                        command.idempotencyKey(),
                        clock.instant())
                .process(clock.instant());
        Payment saved = paymentRepository.save(payment);

        // 업무 ID를 멱등 기록에 즉시 연결합니다. 응답을 만들다 죽어도 무엇을 요청했는지 남습니다.
        idempotencyStore.settle(
                command.memberId().value(),
                PaymentService.OPERATION,
                command.idempotencyKey(),
                IdempotencyStatus.PROCESSING,
                saved.id().value());
        return new Started(saved, true);
    }

    /**
     * 외부 승인을 확정합니다.
     *
     * <p>결제 상태, 결제 분개(JE-013), 멱등 기록, Outbox가 하나의 트랜잭션에서 커밋됩니다.
     * 지갑 잔액은 건드리지 않습니다. 돈이 지갑에서 나가지 않았기 때문입니다.
     */
    @Transactional
    Payment completeApproved(MemberId memberId, Payment payment, String externalReferenceId) {
        Payment current = reload(payment);
        if (current.isApproved()) {
            // 복구와 응답이 겹쳐도 두 번 전기하지 않습니다. 근거: INV-004
            return current;
        }

        Payment approved = current.approveExternally(externalReferenceId, clock.instant());
        LedgerAccount pgReceivable = resolveLedgerAccount.resolve(
                AccountCode.PG_RECEIVABLE,
                pgApprovalPort.providerId(),
                approved.approvedAmount().currency());
        LedgerAccount merchantPayable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_PAYABLE,
                approved.merchantId().value(),
                approved.approvedAmount().currency());

        LedgerTransaction ledgerTransaction = postJournal.post(JournalFactory.pgPaymentApproved(
                approved.id(),
                pgReceivable.id(),
                merchantPayable.id(),
                approved.approvedAmount(),
                approved.approvedAt()));

        Payment saved = paymentRepository.save(approved);
        outboxAppender.append(PaymentEvents.paymentApproved(saved, ledgerTransaction.id()));
        settle(memberId, saved, IdempotencyStatus.COMPLETED);
        return saved;
    }

    /** 외부가 거절했습니다. 원장에는 아무것도 남지 않습니다. 돈이 움직이지 않았기 때문입니다. */
    @Transactional
    Payment completeDeclined(MemberId memberId, Payment payment, String failureReason) {
        Payment current = reload(payment);
        if (current.status().isFinal() || current.isApproved()) {
            return current;
        }
        Payment declined = paymentRepository.save(current.declineExternally(failureReason, clock.instant()));
        settle(memberId, declined, IdempotencyStatus.COMPLETED);
        return declined;
    }

    /** 결과를 모릅니다. 멱등 기록은 PROCESSING으로 남겨 복구가 이어받게 합니다. */
    @Transactional
    Payment markUnknown(MemberId memberId, Payment payment, String externalReferenceId) {
        Payment current = reload(payment);
        if (current.status().isFinal() || current.isApproved()) {
            return current;
        }
        Payment unknown = paymentRepository.save(current.markUnknown(externalReferenceId, clock.instant()));
        settle(memberId, unknown, IdempotencyStatus.PROCESSING);
        return unknown;
    }

    private void settle(MemberId memberId, Payment payment, IdempotencyStatus status) {
        idempotencyStore.settle(
                memberId.value(),
                PaymentService.OPERATION,
                payment.idempotencyKey(),
                status,
                payment.id().value());
    }

    private Payment reload(Payment payment) {
        return paymentRepository
                .findById(payment.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
    }

    record Started(Payment payment, boolean isNew) {}
}
