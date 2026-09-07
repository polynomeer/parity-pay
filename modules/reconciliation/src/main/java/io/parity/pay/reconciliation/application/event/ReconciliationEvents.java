package io.parity.pay.reconciliation.application.event;

import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import io.parity.pay.shared.event.EventEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;

/** 대사 도메인 이벤트. 근거: docs/08-db-api-event-spec.md §7 */
public final class ReconciliationEvents {

    public static final String MISMATCH_DETECTED = "ReconciliationMismatchDetected";

    private ReconciliationEvents() {}

    public static EventEnvelope mismatchDetected(ReconciliationMismatch mismatch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mismatchId", mismatch.mismatchId().toString());
        payload.put("runId", mismatch.runId().toString());
        payload.put("type", mismatch.type().name());
        payload.put("referenceType", mismatch.referenceType());
        payload.put("referenceId", mismatch.referenceId());
        payload.put("amountDifference", mismatch.amountDifference());
        payload.put("currency", mismatch.currency());
        return EventEnvelope.of(
                MISMATCH_DETECTED,
                1,
                "ReconciliationMismatch",
                mismatch.mismatchId().toString(),
                mismatch.detectedAt(),
                null,
                payload);
    }
}
