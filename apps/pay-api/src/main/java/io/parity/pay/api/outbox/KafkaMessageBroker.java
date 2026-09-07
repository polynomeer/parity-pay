package io.parity.pay.api.outbox;

import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Kafka/Redpanda 전송 어댑터. */
@Component
class KafkaMessageBroker implements MessageBroker {

    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, String> kafkaTemplate;

    KafkaMessageBroker(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void send(String topic, String partitionKey, String payload) {
        try {
            // ACK를 기다립니다. 기다리지 않으면 발행하지 않은 이벤트를 PUBLISHED로 표시할 수 있습니다.
            kafkaTemplate.send(topic, partitionKey, payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing event", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish event to " + topic, e);
        }
    }
}
