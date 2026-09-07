package io.parity.pay.api.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행 한 배치의 트랜잭션 경계.
 *
 * <p>스케줄러와 분리되어 있는 이유가 있습니다. 스케줄 메서드가 같은 빈의 {@code @Transactional}
 * 메서드를 호출하면 프록시를 거치지 않아 트랜잭션이 열리지 않습니다. 선점 쿼리는 트랜잭션을
 * 요구하므로(MANDATORY) 매 라운드가 예외로 끝나고, 이벤트는 영원히 발행되지 않습니다.
 *
 * <p>근거: ADR-005, docs/09-consistency-recovery.md §5
 */
@Component
class OutboxPublishTransaction {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublishTransaction.class);

    private final OutboxRepository outboxRepository;
    private final MessageBroker messageBroker;
    private final OutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OutboxPublishTransaction(
            OutboxRepository outboxRepository,
            MessageBroker messageBroker,
            OutboxProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.messageBroker = messageBroker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 한 배치를 발행하고 발행한 건수를 돌려줍니다.
     *
     * <p>선점과 상태 갱신이 하나의 트랜잭션입니다. 중간에 죽으면 그 배치는 통째로 PENDING으로 남고
     * 다음 라운드가 다시 가져갑니다.
     */
    @Transactional
    int publishBatch() {
        Instant now = clock.instant();
        List<OutboxRecord> batch = outboxRepository.claimBatch(properties.batchSize(), now);
        int published = 0;

        for (OutboxRecord record : batch) {
            try {
                messageBroker.send(properties.topic(), record.partitionKey(), toEnvelopeJson(record));
                outboxRepository.markPublished(record.eventId(), clock.instant());
                published++;
            } catch (RuntimeException e) {
                handleFailure(record, e);
            }
        }
        return published;
    }

    private void handleFailure(OutboxRecord record, RuntimeException failure) {
        int nextAttempt = record.attemptCount() + 1;
        if (nextAttempt >= properties.maxAttempts()) {
            log.error(
                    "giving up on outbox event {} after {} attempts", record.eventId(), nextAttempt, failure);
            outboxRepository.markFailed(record.eventId(), failure.getMessage());
            return;
        }
        Duration backoff = backoffFor(nextAttempt);
        log.warn(
                "failed to publish outbox event {} (attempt {}), retrying in {}",
                record.eventId(),
                nextAttempt,
                backoff,
                failure);
        outboxRepository.markForRetry(
                record.eventId(), failure.getMessage(), clock.instant().plus(backoff));
    }

    /** 지수 백오프에 jitter를 더합니다. 여러 발행기가 같은 시각에 몰리는 것을 막습니다. */
    private Duration backoffFor(int attempt) {
        long base = properties.baseBackoff().toMillis() * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(base, properties.maxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(capped / 2 + jitter);
    }

    /** 저장된 payload에 봉투 필드를 씌워 브로커로 보낼 JSON을 만듭니다. */
    private String toEnvelopeJson(OutboxRecord record) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("eventId", record.eventId().toString());
            envelope.put("eventType", record.eventType());
            envelope.put("eventVersion", record.eventVersion());
            envelope.put("aggregateType", record.aggregateType());
            envelope.put("aggregateId", record.aggregateId());
            envelope.put("partitionKey", record.partitionKey());
            envelope.put("occurredAt", record.occurredAt().toString());
            envelope.put("traceId", record.traceId());
            envelope.set("payload", objectMapper.readTree(record.payloadJson()));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to build event envelope for " + record.eventId(), e);
        }
    }
}
