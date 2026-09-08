package io.parity.pay.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.parity.pay.api.outbox.MessageBroker.OutboxMessage;
import io.parity.pay.api.outbox.MessageBroker.SendOutcome;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 브로커 어댑터의 실패 처리.
 *
 * <p>이 테스트는 크래시 실험(F-001·F-002)에서 발견한 결함 때문에 생겼습니다. 브로커가 죽어 있으면
 * {@code send()}는 비동기임에도 메타데이터를 받지 못해 그 자리에서 예외를 던집니다. 그 예외가 배치
 * 밖으로 나가면 발행 라운드 전체가 롤백되어, 실패 횟수도 백오프도 기록되지 않은 채 같은 배치를
 * 계속 다시 시도하게 됩니다. 실험에서는 이벤트 126건이 `attempt_count = 0`인 채 멈춰 있었습니다.
 *
 * <p>근거: reports/11 F-001·F-002, ADR-005
 */
class KafkaMessageBrokerTest {

    private static final String TOPIC = "paritypay.events";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final KafkaMessageBroker broker = new KafkaMessageBroker(kafkaTemplate);

    @Test
    @DisplayName("전송이 그 자리에서 실패해도 예외를 밖으로 던지지 않고 이벤트별 결과로 돌려준다")
    void synchronousSendFailureBecomesAFailedOutcome() {
        OutboxMessage message = message();
        given(kafkaTemplate.send(anyString(), any(), anyString()))
                .willThrow(new KafkaException("Topic not present in metadata", new TimeoutException()));

        List<SendOutcome> outcomes = broker.sendAll(TOPIC, List.of(message));

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).eventId()).isEqualTo(message.eventId());
        assertThat(outcomes.get(0).acknowledged()).isFalse();
        assertThat(outcomes.get(0).failureMessage()).contains("Topic not present in metadata");
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 보낸다")
    void oneFailureDoesNotStopTheRestOfTheBatch() {
        OutboxMessage first = message();
        OutboxMessage second = message();
        OutboxMessage third = message();
        given(kafkaTemplate.send(eq(TOPIC), eq(first.partitionKey()), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        given(kafkaTemplate.send(eq(TOPIC), eq(second.partitionKey()), anyString()))
                .willThrow(new KafkaException("buffer is full"));
        given(kafkaTemplate.send(eq(TOPIC), eq(third.partitionKey()), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        List<SendOutcome> outcomes = broker.sendAll(TOPIC, List.of(first, second, third));

        verify(kafkaTemplate, times(3)).send(anyString(), any(), anyString());
        assertThat(acknowledged(outcomes)).containsExactlyInAnyOrder(first.eventId(), third.eventId());
        assertThat(failed(outcomes)).containsExactly(second.eventId());
    }

    @Test
    @DisplayName("ACK가 실패로 끝나면 그 이벤트만 실패로 기록한다")
    void acknowledgementFailureIsRecordedPerEvent() {
        OutboxMessage acked = message();
        OutboxMessage rejected = message();
        given(kafkaTemplate.send(eq(TOPIC), eq(acked.partitionKey()), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        given(kafkaTemplate.send(eq(TOPIC), eq(rejected.partitionKey()), anyString()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("no acknowledgement")));

        List<SendOutcome> outcomes = broker.sendAll(TOPIC, List.of(acked, rejected));

        assertThat(acknowledged(outcomes)).containsExactly(acked.eventId());
        assertThat(failed(outcomes)).containsExactly(rejected.eventId());
    }

    @Test
    @DisplayName("모든 이벤트에 대해 결과를 하나씩 돌려준다")
    void everyMessageGetsAnOutcome() {
        List<OutboxMessage> messages = List.of(message(), message(), message(), message());
        given(kafkaTemplate.send(anyString(), any(), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        List<SendOutcome> outcomes = broker.sendAll(TOPIC, messages);

        assertThat(outcomes).hasSameSizeAs(messages);
        assertThat(outcomes.stream().map(SendOutcome::eventId))
                .containsExactlyInAnyOrderElementsOf(
                        messages.stream().map(OutboxMessage::eventId).toList());
    }

    private static List<UUID> acknowledged(List<SendOutcome> outcomes) {
        return outcomes.stream()
                .filter(SendOutcome::acknowledged)
                .map(SendOutcome::eventId)
                .toList();
    }

    private static List<UUID> failed(List<SendOutcome> outcomes) {
        return outcomes.stream()
                .filter(outcome -> !outcome.acknowledged())
                .map(SendOutcome::eventId)
                .toList();
    }

    private static OutboxMessage message() {
        UUID eventId = UUID.randomUUID();
        return new OutboxMessage(eventId, "wallet-" + eventId, "{\"eventId\":\"" + eventId + "\"}");
    }
}
