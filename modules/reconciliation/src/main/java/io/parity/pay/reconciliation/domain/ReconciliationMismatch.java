package io.parity.pay.reconciliation.domain;

import io.parity.pay.shared.id.LedgerTransactionId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 대사에서 발견한 차이 한 건.
 *
 * <p>발견 사실은 지우지 않고 해결 상태로 관리합니다. 무엇이 언제 어긋났는지가 사후 조사의 근거이기
 * 때문입니다. 근거: docs/09-consistency-recovery.md §10
 */
public record ReconciliationMismatch(
        UUID mismatchId,
        UUID runId,
        MismatchType type,
        String referenceType,
        String referenceId,
        String externalReferenceId,
        Long internalAmount,
        Long externalAmount,
        String currency,
        String detail,
        ResolutionStatus resolutionStatus,
        String resolutionType,
        String resolvedBy,
        String resolutionReason,
        LedgerTransactionId adjustmentLedgerTransactionId,
        Instant detectedAt,
        Instant resolvedAt) {

    public ReconciliationMismatch {
        Objects.requireNonNull(mismatchId, "mismatchId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
    }

    public static ReconciliationMismatch detected(
            UUID runId,
            MismatchType type,
            String referenceType,
            String referenceId,
            String externalReferenceId,
            Long internalAmount,
            Long externalAmount,
            String currency,
            String detail,
            Instant detectedAt) {
        return new ReconciliationMismatch(
                UUID.randomUUID(),
                runId,
                type,
                referenceType,
                referenceId,
                externalReferenceId,
                internalAmount,
                externalAmount,
                currency,
                detail,
                ResolutionStatus.OPEN,
                null,
                null,
                null,
                null,
                detectedAt,
                null);
    }

    /** 금액 차이입니다. 한쪽만 있는 경우에는 그 값이 그대로 차이입니다. */
    public long amountDifference() {
        long internal = internalAmount == null ? 0L : internalAmount;
        long external = externalAmount == null ? 0L : externalAmount;
        return internal - external;
    }

    public enum ResolutionStatus {
        OPEN,
        RESOLVED,
        /** 원인을 확인했고 조치가 필요 없다고 판단한 경우입니다. 사유가 남습니다. */
        IGNORED
    }
}
