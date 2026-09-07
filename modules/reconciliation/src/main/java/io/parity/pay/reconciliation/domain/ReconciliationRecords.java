package io.parity.pay.reconciliation.domain;

import java.time.Instant;

/**
 * 대사 비교의 입력.
 *
 * <p>비교 키는 내부 업무 ID, 외부 참조 ID, 금액, 통화, 발생 시각입니다.
 * 근거: docs/09-consistency-recovery.md §10
 */
public final class ReconciliationRecords {

    private ReconciliationRecords() {}

    /**
     * 우리 쪽 기록.
     *
     * @param outcome 업무 결과를 대사 관점으로 정규화한 값입니다.
     * @param hasLedgerTransaction 성공한 업무에 대응하는 원장 거래가 있는지 여부입니다.
     */
    public record InternalRecord(
            String referenceType,
            String referenceId,
            String externalReferenceId,
            Outcome outcome,
            long amount,
            String currency,
            boolean hasLedgerTransaction,
            Instant occurredAt) {}

    /** 외부기관 쪽 기록. 외부는 우리 상태를 모르고 자기 사실만 답합니다. */
    public record ExternalRecord(
            String externalReferenceId, Outcome outcome, long amount, String currency, Instant occurredAt) {}

    /** 양쪽 상태를 같은 축으로 놓기 위한 정규화된 결과입니다. */
    public enum Outcome {
        SUCCEEDED,
        FAILED,
        /** 아직 결과를 모르는 상태입니다(UNKNOWN, PROCESSING 등). */
        PENDING
    }
}
