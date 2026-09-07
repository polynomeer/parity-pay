package io.parity.pay.reconciliation.application.port.out;

import io.parity.pay.reconciliation.domain.MismatchType;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRepository {

    void saveRun(
            UUID runId,
            String runType,
            Instant windowStart,
            Instant windowEnd,
            int internalCount,
            int externalCount,
            int mismatchCount,
            Instant startedAt,
            Instant finishedAt);

    /** 실행이 끝난 뒤 확정된 불일치 건수를 기록합니다. */
    void updateRunMismatchCount(UUID runId, int mismatchCount);

    /**
     * 불일치를 기록합니다. 같은 대상의 같은 유형이 이미 미해결로 열려 있으면 추가하지 않습니다.
     *
     * @return 새로 열린 불일치이면 {@code true}
     */
    boolean appendIfAbsent(ReconciliationMismatch mismatch);

    Optional<ReconciliationMismatch> findById(UUID mismatchId);

    List<ReconciliationMismatch> findOpen(MismatchType type, int limit);

    void resolve(ReconciliationMismatch resolved);
}
