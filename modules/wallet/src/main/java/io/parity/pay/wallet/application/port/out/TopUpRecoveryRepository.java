package io.parity.pay.wallet.application.port.out;

import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.wallet.domain.TopUpStatus;
import java.time.Instant;
import java.util.List;

/**
 * 미확정 충전의 복구 스케줄 저장소.
 *
 * <p>복구 상태는 업무 Aggregate가 아니라 운영 관심사이므로 별도 표에 둡니다. 정상적으로 끝난 충전은
 * 이 표에 흔적을 남기지 않습니다. 근거: docs/09-consistency-recovery.md §8
 */
public interface TopUpRecoveryRepository {

    /**
     * 복구 대상을 선점하고 다음 점검 시각을 리스 기간만큼 미룹니다.
     *
     * <p>리스를 걸어두는 이유는, 외부 조회를 하는 동안 다른 인스턴스가 같은 거래를 집어가 외부에
     * 중복 조회를 보내지 않게 하기 위해서입니다. 리스가 만료되면 다시 대상이 되므로, 조회 중
     * 프로세스가 죽어도 거래가 영원히 묻히지 않습니다.
     */
    List<PendingRecovery> claimDue(Instant now, Instant graceCutoff, Instant leaseUntil, int limit);

    /** 다음 시도를 예약합니다. */
    void scheduleRetry(
            TopUpId topUpId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            String lastError,
            Instant checkedAt);

    /** 자동 재시도를 중단하고 운영자·대사 대상으로 넘깁니다. */
    void markManualReview(TopUpId topUpId, int attemptCount, String lastError, Instant checkedAt);

    /** 최종 상태로 확정되어 더 이상 복구 대상이 아닙니다. */
    void clear(TopUpId topUpId);

    /** 운영자가 확인해야 하는 건들입니다. */
    List<PendingRecovery> findManualReview(int limit);

    record PendingRecovery(
            TopUpId topUpId,
            TopUpStatus status,
            int attemptCount,
            int notFoundCount,
            boolean requiresManualReview) {}
}
