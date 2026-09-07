package io.parity.pay.api.outbox;

import java.time.Instant;
import java.util.UUID;

/** Outbox 테이블 한 행. 발행기 내부에서만 사용합니다. */
record OutboxRecord(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String partitionKey,
        String payloadJson,
        String traceId,
        int attemptCount,
        Instant occurredAt) {}
