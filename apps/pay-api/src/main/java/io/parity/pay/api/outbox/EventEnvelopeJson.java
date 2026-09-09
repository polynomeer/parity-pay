package io.parity.pay.api.outbox;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 봉투 JSON을 만드는 한 곳.
 *
 * <p>기록할 때(계약 검사)와 발행할 때(브로커 전송) 같은 모양이어야 합니다. 두 군데에서 각자 만들면
 * 검사한 모양과 실제로 나가는 모양이 서로 달라질 수 있습니다. 근거: docs/08-db-api-event-spec.md §6
 */
final class EventEnvelopeJson {

    private EventEnvelopeJson() {}

    static ObjectNode build(
            ObjectMapper objectMapper,
            UUID eventId,
            String eventType,
            int eventVersion,
            String aggregateType,
            String aggregateId,
            String partitionKey,
            Instant occurredAt,
            String traceId,
            JsonNode payload) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", eventVersion);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("partitionKey", partitionKey);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("traceId", traceId);
        envelope.set("payload", payload);
        return envelope;
    }
}
