package io.parity.pay.payment.application.service;

import io.parity.pay.payment.application.port.out.CancellationRecoveryRepository;
import io.parity.pay.payment.application.port.out.CancellationRecoveryRepository.PendingRecovery;
import io.parity.pay.payment.application.port.out.PaymentCancellationRepository;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.application.port.out.PgRefundPort;
import io.parity.pay.payment.application.port.out.PgRefundPort.RefundStatus;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.CancellationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 미확정 환불 복구 작업.
 *
 * <p>결제 복구와 같은 규칙이며, 여기서 더 조심할 것이 하나 있습니다. **환불을 다시 보내지
 * 않습니다.** 승인 재요청은 이중 청구를 만들고, 환불 재요청은 이중 환불을 만듭니다. 둘 다 조회로만
 * 확정합니다.
 *
 * <p>근거: ADR-007, docs/09-consistency-recovery.md §7·§8 (F-007)
 */
@Service
public class CancellationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CancellationRecoveryService.class);

    private final CancellationRecoveryRepository recoveryRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final PaymentRepository paymentRepository;
    private final PgRefundPort pgRefundPort;
    private final CancellationTransactions transactions;
    private final CancellationRecoveryProperties properties;
    private final Clock clock;

    public CancellationRecoveryService(
            CancellationRecoveryRepository recoveryRepository,
            PaymentCancellationRepository cancellationRepository,
            PaymentRepository paymentRepository,
            PgRefundPort pgRefundPort,
            CancellationTransactions transactions,
            CancellationRecoveryProperties properties,
            Clock clock) {
        this.recoveryRepository = recoveryRepository;
        this.cancellationRepository = cancellationRepository;
        this.paymentRepository = paymentRepository;
        this.pgRefundPort = pgRefundPort;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paritypay.recovery.cancellation.interval-ms:5000}")
    void resolveScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            resolveDue();
        } catch (RuntimeException e) {
            log.error("cancellation recovery round failed", e);
        }
    }

    public int resolveDue() {
        Instant now = clock.instant();
        List<PendingRecovery> pending = recoveryRepository.claimDue(
                now, now.minus(properties.grace()), now.plus(properties.lease()), properties.batchSize());

        int settled = 0;
        for (PendingRecovery item : pending) {
            if (resolveOne(item)) {
                settled++;
            }
        }
        return settled;
    }

    /** 운영자가 특정 건을 즉시 재조회하도록 요청할 때 사용합니다. 환불을 다시 보내지 않습니다. */
    public RecoveryOutcome resolveNow(CancellationId cancellationId) {
        PaymentCancellation cancellation = loadCancellation(cancellationId);
        if (cancellation.status().isFinal()) {
            return new RecoveryOutcome(cancellationId, cancellation.status().name(), false, "already final");
        }
        boolean changed = resolveOne(new PendingRecovery(cancellationId, cancellation.status(), 0, 0, false));
        return new RecoveryOutcome(
                cancellationId,
                loadCancellation(cancellationId).status().name(),
                changed,
                changed ? "settled" : "still unknown");
    }

    private boolean resolveOne(PendingRecovery item) {
        PaymentCancellation cancellation = loadCancellation(item.cancellationId());
        if (cancellation.status().isFinal()) {
            recoveryRepository.clear(item.cancellationId());
            return false;
        }
        Payment payment = loadPayment(cancellation);

        RefundStatus status;
        try {
            status = pgRefundPort.getStatus(cancellation.id());
        } catch (RuntimeException e) {
            log.warn("refund status query failed for cancellation {}", cancellation.id(), e);
            status = RefundStatus.UNAVAILABLE;
        }

        return switch (status) {
            case REFUNDED -> {
                transactions.completeRefunded(
                        payment.memberId(), cancellation, payment, cancellation.externalReferenceId());
                recoveryRepository.clear(cancellation.id());
                log.info("recovered cancellation {} as COMPLETED", cancellation.id());
                yield true;
            }
            case DECLINED -> {
                transactions.completeDeclined(payment.memberId(), cancellation, payment, "EXTERNAL_DECLINED");
                recoveryRepository.clear(cancellation.id());
                log.info("recovered cancellation {} as FAILED", cancellation.id());
                yield true;
            }
            case NOT_FOUND -> handleNotFound(item, cancellation, payment);
            case UNAVAILABLE -> {
                scheduleRetryOrEscalate(item, "refund status query unavailable");
                yield false;
            }
        };
    }

    /**
     * 외부에 환불 기록이 없습니다.
     *
     * <p>연속으로 확인돼야 실패로 확정합니다. 한 번의 "없음"으로 예약을 풀면, 실제로는 환불이 나간
     * 뒤였을 때 같은 금액을 다시 환불할 수 있게 됩니다.
     */
    private boolean handleNotFound(PendingRecovery item, PaymentCancellation cancellation, Payment payment) {
        int notFoundCount = item.notFoundCount() + 1;
        if (notFoundCount >= properties.notFoundConfirmThreshold()) {
            transactions.completeDeclined(payment.memberId(), cancellation, payment, "EXTERNAL_RECORD_NOT_FOUND");
            recoveryRepository.clear(cancellation.id());
            log.info(
                    "settled cancellation {} as FAILED after {} consecutive not-found results",
                    cancellation.id(),
                    notFoundCount);
            return true;
        }
        int attemptCount = item.attemptCount() + 1;
        recoveryRepository.scheduleRetry(
                cancellation.id(),
                attemptCount,
                notFoundCount,
                clock.instant().plus(backoffFor(attemptCount)),
                "external refund record not found",
                clock.instant());
        return false;
    }

    private void scheduleRetryOrEscalate(PendingRecovery item, String reason) {
        int attemptCount = item.attemptCount() + 1;
        if (attemptCount >= properties.maxAttempts()) {
            recoveryRepository.markManualReview(item.cancellationId(), attemptCount, reason, clock.instant());
            log.warn(
                    "cancellation {} needs manual review after {} attempts: {}",
                    item.cancellationId(),
                    attemptCount,
                    reason);
            return;
        }
        recoveryRepository.scheduleRetry(
                item.cancellationId(),
                attemptCount,
                item.notFoundCount(),
                clock.instant().plus(backoffFor(attemptCount)),
                reason,
                clock.instant());
    }

    private Duration backoffFor(int attempt) {
        long base = properties.baseBackoff().toMillis() * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(base, properties.maxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(capped / 2 + jitter);
    }

    private PaymentCancellation loadCancellation(CancellationId cancellationId) {
        return cancellationRepository
                .findById(cancellationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "cancellation not found"));
    }

    private Payment loadPayment(PaymentCancellation cancellation) {
        return paymentRepository
                .findById(cancellation.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
    }

    public record RecoveryOutcome(CancellationId cancellationId, String status, boolean changed, String detail) {}
}
