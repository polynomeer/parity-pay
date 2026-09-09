package io.parity.pay.api.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
     * 한 배치를 발행하고 확인된 건수를 돌려줍니다.
     *
     * <p>선점과 상태 갱신이 하나의 트랜잭션입니다. 중간에 죽으면 그 배치는 통째로 PENDING으로 남고
     * 다음 라운드가 다시 가져갑니다.
     *
     * <p>전송은 배치로 하되 표시는 확인 뒤에 합니다. 브로커가 받았다고 답한 이벤트만 PUBLISHED로
     * 바꾸고, 실패한 이벤트는 개별적으로 백오프 재시도로 돌립니다. 배치 하나가 부분 실패했다고
     * 성공한 것까지 되돌리면 중복 발행만 늘어납니다.
     */
    @Transactional
    int publishBatch() {
        Instant now = clock.instant();
        List<OutboxRecord> batch = outboxRepository.claimBatch(properties.batchSize(), now);
        if (batch.isEmpty()) {
            return 0;
        }

        Map<UUID, OutboxRecord> claimed = new LinkedHashMap<>();
        List<MessageBroker.OutboxMessage> messages = new ArrayList<>(batch.size());
        for (OutboxRecord record : batch) {
            try {
                String envelope = toEnvelopeJson(record);
                claimed.put(record.eventId(), record);
                messages.add(new MessageBroker.OutboxMessage(record.eventId(), record.partitionKey(), envelope));
            } catch (RuntimeException e) {
                // 봉투를 만들지 못하는 이벤트는 보내봐야 소용이 없습니다. 배치 전체를 막지 않고
                // 그 건만 재시도 경로로 보냅니다.
                handleFailure(record, e.getMessage());
            }
        }
        if (messages.isEmpty()) {
            return 0;
        }

        List<MessageBroker.SendOutcome> outcomes = messageBroker.sendAll(properties.topic(), messages);
        List<UUID> acknowledged = new ArrayList<>(outcomes.size());
        for (MessageBroker.SendOutcome outcome : outcomes) {
            OutboxRecord record = claimed.get(outcome.eventId());
            if (record == null) {
                continue;
            }
            if (outcome.acknowledged()) {
                acknowledged.add(outcome.eventId());
            } else {
                handleFailure(record, outcome.failureMessage());
            }
        }

        if (!acknowledged.isEmpty()) {
            outboxRepository.markPublished(acknowledged, clock.instant());
        }
        return acknowledged.size();
    }

    private void handleFailure(OutboxRecord record, String failureMessage) {
        int nextAttempt = record.attemptCount() + 1;
        if (nextAttempt >= properties.maxAttempts()) {
            log.error(
                    "giving up on outbox event {} after {} attempts: {}",
                    record.eventId(),
                    nextAttempt,
                    failureMessage);
            outboxRepository.markFailed(record.eventId(), failureMessage);
            return;
        }
        Duration backoff = backoffFor(nextAttempt);
        log.warn(
                "failed to publish outbox event {} (attempt {}), retrying in {}: {}",
                record.eventId(),
                nextAttempt,
                backoff,
                failureMessage);
        outboxRepository.markForRetry(
                record.eventId(), failureMessage, clock.instant().plus(backoff));
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
            return objectMapper.writeValueAsString(EventEnvelopeJson.build(
                    objectMapper,
                    record.eventId(),
                    record.eventType(),
                    record.eventVersion(),
                    record.aggregateType(),
                    record.aggregateId(),
                    record.partitionKey(),
                    record.occurredAt(),
                    record.traceId(),
                    objectMapper.readTree(record.payloadJson())));
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to build event envelope for " + record.eventId(), e);
        }
    }
}
