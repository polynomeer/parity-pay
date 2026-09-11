package io.parity.pay.payment.application.service;

import io.parity.pay.payment.application.port.out.PaymentRecoveryRepository;
import io.parity.pay.payment.application.port.out.PaymentRecoveryRepository.PendingRecovery;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.application.port.out.PgApprovalPort;
import io.parity.pay.payment.application.port.out.PgApprovalPort.ApprovalStatus;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.PaymentId;
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
 * 미확정 결제 복구 작업.
 *
 * <p>충전 복구(F-006)와 같은 규칙입니다. 다른 것은 확정 대상뿐입니다.
 *
 * <ul>
 *   <li>같은 승인을 다시 보내지 않고 조회를 먼저 합니다. 외부 멱등성이 불완전하면 재요청은 이중
 *       청구가 됩니다.
 *   <li>조회가 승인·거절을 알려주면 정상 흐름과 **같은 트랜잭션 메서드**로 확정합니다. 원장과
 *       이벤트가 함께 커밋되고, 이미 확정된 건은 그대로 통과합니다(INV-004).
 *   <li>외부에 기록이 "없음"으로 연속 확인되면 청구가 없었다고 보고 거절로 확정합니다.
 *   <li>조회 자체가 실패하면 아무것도 확정하지 않고 백오프 후 다시 시도합니다(F-009).
 *   <li>재시도 한도를 넘겨도 불명확하면 자동 재시도를 멈추고 사람에게 넘깁니다.
 * </ul>
 *
 * <p>근거: ADR-007, docs/09-consistency-recovery.md §7·§8
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentRecoveryRepository recoveryRepository;
    private final PaymentRepository paymentRepository;
    private final PgApprovalPort pgApprovalPort;
    private final PaymentTransactions transactions;
    private final PaymentRecoveryProperties properties;
    private final Clock clock;

    public PaymentRecoveryService(
            PaymentRecoveryRepository recoveryRepository,
            PaymentRepository paymentRepository,
            PgApprovalPort pgApprovalPort,
            PaymentTransactions transactions,
            PaymentRecoveryProperties properties,
            Clock clock) {
        this.recoveryRepository = recoveryRepository;
        this.paymentRepository = paymentRepository;
        this.pgApprovalPort = pgApprovalPort;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paritypay.recovery.payment.interval-ms:5000}")
    void resolveScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            drainDue();
        } catch (RuntimeException e) {
            log.error("payment recovery round failed", e);
        }
    }

    /**
     * 적체가 남아 있는 동안 배치를 이어서 처리하고, 확정된 건수의 합을 돌려줍니다.
     *
     * <p>배치가 가득 찼다는 것은 적체가 더 있다는 뜻입니다. 다음 틱을 기다리지 않고 이어서
     * 처리합니다. 틱당 한 배치면 기계·기관이 아무리 빨라도 초당 10건이 상한이고, 그 위에서는
     * 클라이언트가 90초 안에 답을 받지 못합니다(M-012). Outbox 발행기와 같은 판단입니다.
     * 기관이 죽어 있으면 배치 하나에 읽기 타임아웃 × 50이 걸리는데, 그것은 전과 같습니다. 이
     * 루프는 배치 사이의 5초 공백을 없앨 뿐이고, 한 틱의 길이는 maxRoundsPerTick이 막습니다.
     */
    public int drainDue() {
        int settled = 0;
        for (int round = 0; round < properties.maxRoundsPerTick(); round++) {
            Round result = resolveBatch();
            settled += result.settled();
            if (result.claimed() < properties.batchSize()) {
                break;
            }
        }
        return settled;
    }

    /**
     * 복구 대상을 한 배치 처리하고 확정된 건수를 돌려줍니다.
     *
     * <p>선점(트랜잭션 1) → 외부 조회(트랜잭션 밖) → 확정(건별 트랜잭션) 순서입니다.
     */
    public int resolveDue() {
        return resolveBatch().settled();
    }

    /** 한 배치의 결과입니다. 선점한 건수가 배치 크기와 같으면 적체가 더 남아 있습니다. */
    private record Round(int claimed, int settled) {}

    private Round resolveBatch() {
        Instant now = clock.instant();
        List<PendingRecovery> pending = recoveryRepository.claimDue(
                now, now.minus(properties.grace()), now.plus(properties.lease()), properties.batchSize());

        int settled = 0;
        for (PendingRecovery item : pending) {
            if (resolveOne(item)) {
                settled++;
            }
        }
        return new Round(pending.size(), settled);
    }

    /** 운영자가 특정 건을 즉시 재조회하도록 요청할 때 사용합니다. 외부에 승인을 다시 보내지 않습니다. */
    public RecoveryOutcome resolveNow(PaymentId paymentId) {
        Payment payment = loadPayment(paymentId);
        if (payment.status().isFinal() || payment.isApproved()) {
            return new RecoveryOutcome(paymentId, payment.status().name(), false, "already final");
        }
        boolean changed = resolveOne(new PendingRecovery(paymentId, payment.status(), 0, 0, false));
        return new RecoveryOutcome(
                paymentId, loadPayment(paymentId).status().name(), changed, changed ? "settled" : "still unknown");
    }

    private boolean resolveOne(PendingRecovery item) {
        Payment payment = loadPayment(item.paymentId());
        if (payment.status().isFinal() || payment.isApproved()) {
            recoveryRepository.clear(item.paymentId());
            return false;
        }

        ApprovalStatus status;
        try {
            status = pgApprovalPort.getStatus(payment.id());
        } catch (RuntimeException e) {
            log.warn("status query failed for payment {}", payment.id(), e);
            status = ApprovalStatus.UNAVAILABLE;
        }

        return switch (status) {
            case APPROVED -> {
                transactions.completeApproved(
                        payment.memberId(), payment, payment.id().toString());
                recoveryRepository.clear(payment.id());
                log.info("recovered payment {} as APPROVED", payment.id());
                yield true;
            }
            case DECLINED -> {
                transactions.completeDeclined(payment.memberId(), payment, "EXTERNAL_DECLINED");
                recoveryRepository.clear(payment.id());
                log.info("recovered payment {} as FAILED", payment.id());
                yield true;
            }
            case NOT_FOUND -> handleNotFound(item, payment);
            case UNAVAILABLE -> {
                scheduleRetryOrEscalate(item, "status query unavailable");
                yield false;
            }
        };
    }

    /**
     * 외부에 승인 기록이 없습니다.
     *
     * <p>한 번의 "없음"으로 거절을 확정하지 않습니다. 외부가 요청을 받고 기록하기 직전일 수도 있고,
     * 그 상태에서 거절로 적으면 청구된 결제를 실패로 알리게 됩니다.
     */
    private boolean handleNotFound(PendingRecovery item, Payment payment) {
        int notFoundCount = item.notFoundCount() + 1;
        if (notFoundCount >= properties.notFoundConfirmThreshold()) {
            transactions.completeDeclined(payment.memberId(), payment, "EXTERNAL_RECORD_NOT_FOUND");
            recoveryRepository.clear(payment.id());
            log.info(
                    "settled payment {} as FAILED after {} consecutive not-found results", payment.id(), notFoundCount);
            return true;
        }
        int attemptCount = item.attemptCount() + 1;
        recoveryRepository.scheduleRetry(
                payment.id(),
                attemptCount,
                notFoundCount,
                clock.instant().plus(backoffFor(attemptCount)),
                "external record not found",
                clock.instant());
        return false;
    }

    private void scheduleRetryOrEscalate(PendingRecovery item, String reason) {
        int attemptCount = item.attemptCount() + 1;
        if (attemptCount >= properties.maxAttempts()) {
            // 자동으로 안전하게 확정할 수 없습니다. 외부를 계속 두드리는 대신 사람에게 넘깁니다.
            recoveryRepository.markManualReview(item.paymentId(), attemptCount, reason, clock.instant());
            log.warn("payment {} needs manual review after {} attempts: {}", item.paymentId(), attemptCount, reason);
            return;
        }
        recoveryRepository.scheduleRetry(
                item.paymentId(),
                attemptCount,
                item.notFoundCount(),
                clock.instant().plus(backoffFor(attemptCount)),
                reason,
                clock.instant());
    }

    /** 지수 백오프에 jitter를 더합니다. 여러 인스턴스가 같은 시각에 외부를 두드리지 않게 합니다. */
    private Duration backoffFor(int attempt) {
        long base = properties.baseBackoff().toMillis() * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(base, properties.maxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(capped / 2 + jitter);
    }

    private Payment loadPayment(PaymentId paymentId) {
        return paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
    }

    public record RecoveryOutcome(PaymentId paymentId, String status, boolean changed, String detail) {}
}
