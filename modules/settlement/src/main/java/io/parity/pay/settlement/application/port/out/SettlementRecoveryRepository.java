package io.parity.pay.settlement.application.port.out;

import io.parity.pay.shared.id.SettlementId;
import java.time.Instant;
import java.util.List;

/** 지급 결과가 불명확한 정산의 복구 스케줄. 충전 복구와 같은 구조입니다. */
public interface SettlementRecoveryRepository {

    List<PendingPayoutRecovery> claimDue(Instant now, Instant leaseUntil, int limit);

    void scheduleRetry(
            SettlementId settlementId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            String lastError,
            Instant checkedAt);

    void markManualReview(SettlementId settlementId, int attemptCount, String lastError, Instant checkedAt);

    void clear(SettlementId settlementId);

    record PendingPayoutRecovery(SettlementId settlementId, int attemptCount, int notFoundCount) {}
}
