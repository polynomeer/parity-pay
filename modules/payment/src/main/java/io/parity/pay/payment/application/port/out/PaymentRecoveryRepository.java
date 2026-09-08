package io.parity.pay.payment.application.port.out;

import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.id.PaymentId;
import java.time.Instant;
import java.util.List;

/**
 * 미확정 결제의 복구 스케줄 저장소.
 *
 * <p>충전 복구와 같은 모양입니다. 다른 것은 복구가 확정하는 대상뿐입니다.
 * 근거: docs/09-consistency-recovery.md §8
 */
public interface PaymentRecoveryRepository {

    /**
     * 복구 대상을 선점하고 다음 점검 시각을 리스 기간만큼 미룹니다.
     *
     * <p>리스가 없으면 외부 조회를 하는 동안 다른 인스턴스가 같은 결제를 집어가 외부에 중복 조회를
     * 보냅니다. 리스가 만료되면 다시 대상이 되므로 조회 중 프로세스가 죽어도 묻히지 않습니다.
     */
    List<PendingRecovery> claimDue(Instant now, Instant graceCutoff, Instant leaseUntil, int limit);

    void scheduleRetry(
            PaymentId paymentId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            String lastError,
            Instant checkedAt);

    /** 자동 재시도를 중단하고 운영자·대사 대상으로 넘깁니다. */
    void markManualReview(PaymentId paymentId, int attemptCount, String lastError, Instant checkedAt);

    void clear(PaymentId paymentId);

    List<PendingRecovery> findManualReview(int limit);

    record PendingRecovery(
            PaymentId paymentId,
            PaymentStatus status,
            int attemptCount,
            int notFoundCount,
            boolean requiresManualReview) {}
}
