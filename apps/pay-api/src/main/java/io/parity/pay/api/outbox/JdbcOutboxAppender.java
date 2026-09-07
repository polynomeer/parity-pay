package io.parity.pay.api.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.EventEnvelope;
import io.parity.pay.shared.event.OutboxAppender;
import java.time.Clock;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트를 업무 트랜잭션과 같은 트랜잭션에 기록합니다.
 *
 * <p>{@link Propagation#MANDATORY}로 트랜잭션 밖 호출을 막습니다. 업무 커밋 없이 이벤트만 남거나,
 * 이벤트 없이 업무만 커밋되는 창을 만들지 않기 위해서입니다. 근거: ADR-005
 */
@Component
class JdbcOutboxAppender implements OutboxAppender {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final EventSchemaValidator schemaValidator;
    private final OutboxProperties properties;
    private final Clock clock;

    JdbcOutboxAppender(
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            EventSchemaValidator schemaValidator,
            OutboxProperties properties,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(EventEnvelope envelope) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(envelope.payload());
        } catch (JsonProcessingException e) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "failed to serialize event payload: " + envelope.eventType());
        }

        String traceId = envelope.traceId() != null ? envelope.traceId() : MDC.get("traceId");

        if (properties.validateSchema()) {
            // 계약 위반이면 이벤트를 기록하지 않고 업무 트랜잭션까지 되돌립니다. 깨진 이벤트를
            // 남기고 발행 단계에서 막으면 이미 커밋된 업무와 발행할 수 없는 이벤트가 남습니다.
            schemaValidator.validate(EventEnvelopeJson.build(
                    objectMapper,
                    envelope.eventId().value(),
                    envelope.eventType(),
                    envelope.eventVersion(),
                    envelope.aggregateType(),
                    envelope.aggregateId(),
                    envelope.partitionKey(),
                    envelope.occurredAt(),
                    traceId,
                    objectMapper.valueToTree(envelope.payload())));
        }

        outboxRepository.append(
                envelope.eventId().value(),
                envelope.eventType(),
                envelope.eventVersion(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.partitionKey(),
                payloadJson,
                traceId,
                envelope.occurredAt(),
                clock.instant());
    }
}
