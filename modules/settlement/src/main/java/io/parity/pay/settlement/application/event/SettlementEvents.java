package io.parity.pay.settlement.application.event;

import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.shared.event.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 정산 도메인 이벤트. 근거: docs/08-db-api-event-spec.md §7 */
public final class SettlementEvents {

    public static final String SETTLEMENT_CREATED = "SettlementCreated";
    public static final String SETTLEMENT_PAID = "SettlementPaid";

    private SettlementEvents() {}

    public static EventEnvelope settlementCreated(Settlement settlement, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlement.id().toString());
        payload.put("merchantId", settlement.merchantId().toString());
        payload.put("periodStart", settlement.periodStart().toString());
        payload.put("periodEnd", settlement.periodEnd().toString());
        payload.put("netAmount", settlement.netAmount().amount());
        payload.put("currency", settlement.currency().name());
        return EventEnvelope.of(
                SETTLEMENT_CREATED, 1, "Settlement", settlement.id().toString(), occurredAt, null, payload);
    }

    public static EventEnvelope settlementPaid(Settlement settlement, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlement.id().toString());
        payload.put("merchantId", settlement.merchantId().toString());
        payload.put("netAmount", settlement.netAmount().amount());
        payload.put("currency", settlement.currency().name());
        payload.put("externalReferenceId", settlement.externalReferenceId());
        return EventEnvelope.of(
                SETTLEMENT_PAID, 1, "Settlement", settlement.id().toString(), occurredAt, null, payload);
    }
}
