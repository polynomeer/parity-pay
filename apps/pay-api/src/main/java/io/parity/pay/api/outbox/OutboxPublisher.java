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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 발행기.
 *
 * <p>커밋된 이벤트를 브로커로 옮깁니다. 발행 도중 프로세스가 죽어도 이벤트는 여전히 PENDING으로
 * 남아 있으므로, 재시작한 발행기가 이어서 발행합니다(F-003).
 *
 * <p>브로커 ACK가 유실되면 같은 이벤트가 두 번 발행될 수 있습니다. 이것은 버그가 아니라 전제이며,
 * 중복은 소비자가 흡수합니다. 근거: ADR-005, ADR-006
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final MessageBroker messageBroker;
    private final OutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OutboxPublisher(
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

    @Scheduled(fixedDelayString = "${paritypay.events.poll-interval-ms:500}")
    void publishScheduled() {
        if (!properties.publisherEnabled()) {
            return;
        }
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.error("outbox publishing round failed", e);
        }
    }

    /**
     * 한 배치를 발행하고 발행한 건수를 돌려줍니다.
     *
     * <p>선점과 상태 갱신이 하나의 트랜잭션입니다. 배치 전체가 커밋되므로, 중간에 죽으면 그 배치는
     * 통째로 PENDING으로 남고 다음 라운드가 다시 가져갑니다.
     */
    @Transactional
    public int publishBatch() {
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
