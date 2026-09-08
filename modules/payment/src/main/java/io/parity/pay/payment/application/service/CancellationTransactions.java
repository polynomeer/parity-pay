package io.parity.pay.payment.application.service;

import io.parity.pay.ledger.application.port.in.LedgerBalanceQuery;
import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.payment.application.event.PaymentEvents;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.application.port.out.PaymentCancellationRepository;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.application.port.out.PgApprovalPort;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.idempotency.IdempotencyStatus;
import io.parity.pay.shared.idempotency.IdempotencyStore;
import io.parity.pay.shared.money.Money;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 PG 환불의 트랜잭션 경계.
 *
 * <p>결제 승인과 같은 모양입니다. 예약을 먼저 커밋하고, 외부에 환불을 요청하고, 결과를 따로
 * 확정합니다. 예약이 먼저인 이유는 두 가지입니다. 동시 취소가 승인액을 넘지 못하게 막아야 하고
 * (INV-005), 프로세스가 죽어도 "환불을 요청했다"는 사실이 남아야 복구가 이어받습니다.
 *
 * <p>근거: ADR-007, docs/09-consistency-recovery.md §7, docs/07-ledger-journal-catalog.md JE-014
 */
@Component
class CancellationTransactions {

    private final PaymentRepository paymentRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final PgApprovalPort pgApprovalPort;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final LedgerBalanceQuery ledgerBalanceQuery;
    private final IdempotencyStore idempotencyStore;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    CancellationTransactions(
            PaymentRepository paymentRepository,
            PaymentCancellationRepository cancellationRepository,
            PgApprovalPort pgApprovalPort,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            LedgerBalanceQuery ledgerBalanceQuery,
            IdempotencyStore idempotencyStore,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.cancellationRepository = cancellationRepository;
        this.pgApprovalPort = pgApprovalPort;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.ledgerBalanceQuery = ledgerBalanceQuery;
        this.idempotencyStore = idempotencyStore;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    /** 취소 가능액을 예약하고 취소를 PROCESSING으로 커밋합니다. */
    @Transactional
    PaymentCancellation begin(CancelPaymentCommand command, Payment payment) {
        if (paymentRepository.reserveCancellation(payment.id(), command.amount()) != 1) {
            throw new BusinessException(
                    ErrorCode.CANCELLATION_AMOUNT_EXCEEDED,
                    "cancellation amount exceeds the remaining cancellable amount");
        }
        PaymentCancellation cancellation = PaymentCancellation.request(
                        payment.id(), command.amount(), command.reason(), command.idempotencyKey(), clock.instant())
                .begin();
        cancellationRepository.save(cancellation);

        idempotencyStore.settle(
                command.memberId().value(),
                PaymentCancellationService.OPERATION,
                command.idempotencyKey(),
                IdempotencyStatus.PROCESSING,
                cancellation.id().value());
        return cancellation;
    }

    /**
     * 외부 환불을 확정합니다.
     *
     * <p>예약을 확정 취소액으로 옮기고, 상쇄 분개(JE-014)를 전기하고, 이벤트를 남깁니다. 지갑
     * 잔액은 건드리지 않습니다. 돈은 카드로 돌아갑니다.
     */
    @Transactional
    PaymentCancellation completeRefunded(
            MemberId memberId, PaymentCancellation cancellation, Payment payment, String externalReferenceId) {
        PaymentCancellation current = reload(cancellation.id());
        if (current.status().isFinal()) {
            return current;
        }

        if (paymentRepository.completeCancellation(payment.id(), current.requestedAmount()) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "reserved cancellation amount disappeared");
        }

        Money amount = current.requestedAmount();
        LedgerAccount merchantPayable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_PAYABLE, payment.merchantId().value(), amount.currency());
        LedgerAccount merchantReceivable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_RECEIVABLE, payment.merchantId().value(), amount.currency());
        LedgerAccount pgReceivable =
                resolveLedgerAccount.resolve(AccountCode.PG_RECEIVABLE, pgApprovalPort.providerId(), amount.currency());

        // 이미 정산이 지급되어 지급예정금이 남아 있지 않으면 부족분을 판매자 미수금으로 기록합니다.
        // 페이머니 취소(JE-009)와 같은 처리입니다.
        Money payableBalance = ledgerBalanceQuery.balanceOf(merchantPayable.id());
        Money fromPayable = payableBalance.isGreaterThanOrEqualTo(amount) ? amount : payableBalance;
        Money fromReceivable = amount.minus(fromPayable);

        PaymentCancellation completed = current.completeExternally(externalReferenceId, clock.instant());
        LedgerTransaction ledgerTransaction = postJournal.post(JournalFactory.pgPaymentRefunded(
                completed.id(),
                merchantPayable.id(),
                merchantReceivable.id(),
                pgReceivable.id(),
                fromPayable,
                fromReceivable,
                completed.completedAt()));

        cancellationRepository.save(completed);
        outboxAppender.append(PaymentEvents.cancellationCompleted(
                completed, payment.walletId(), payment.merchantId(), ledgerTransaction.id()));
        settle(memberId, completed, IdempotencyStatus.COMPLETED);
        return completed;
    }

    /**
     * 외부가 환불을 거절했습니다.
     *
     * <p>예약해 둔 금액을 풀어 줍니다. 풀지 않으면 그만큼 다시 취소할 수 없게 됩니다.
     */
    @Transactional
    PaymentCancellation completeDeclined(
            MemberId memberId, PaymentCancellation cancellation, Payment payment, String failureReason) {
        PaymentCancellation current = reload(cancellation.id());
        if (current.status().isFinal()) {
            return current;
        }
        paymentRepository.releaseCancellation(payment.id(), current.requestedAmount());
        // 거절 사유는 취소 행이 아니라 로그·감사에 남습니다. 취소 행에는 상태만 남깁니다.
        PaymentCancellation failed = current.fail(clock.instant());
        cancellationRepository.save(failed);
        settle(memberId, failed, IdempotencyStatus.COMPLETED);
        return failed;
    }

    /**
     * 환불 결과를 모릅니다.
     *
     * <p>예약을 풀지 않습니다. 돈이 이미 돌아갔을 수 있는데 예약을 풀면 같은 금액을 다시 취소할 수
     * 있게 되고, 그것이 이중 환불이 됩니다.
     */
    @Transactional
    PaymentCancellation markUnknown(MemberId memberId, PaymentCancellation cancellation, String externalReferenceId) {
        PaymentCancellation current = reload(cancellation.id());
        if (current.status().isFinal()) {
            return current;
        }
        PaymentCancellation unknown = current.markUnknown(externalReferenceId, clock.instant());
        cancellationRepository.save(unknown);
        settle(memberId, unknown, IdempotencyStatus.PROCESSING);
        return unknown;
    }

    private void settle(MemberId memberId, PaymentCancellation cancellation, IdempotencyStatus status) {
        idempotencyStore.settle(
                memberId.value(),
                PaymentCancellationService.OPERATION,
                cancellation.idempotencyKey(),
                status,
                cancellation.id().value());
    }

    private PaymentCancellation reload(CancellationId cancellationId) {
        return cancellationRepository
                .findById(cancellationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "cancellation not found"));
    }
}
