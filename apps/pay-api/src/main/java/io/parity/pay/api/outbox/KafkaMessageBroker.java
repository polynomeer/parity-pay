package io.parity.pay.api.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Kafka/Redpanda 전송 어댑터.
 *
 * <p>배치의 모든 레코드를 먼저 보내고, 그다음에 모든 응답을 기다립니다. 건별로 보내고 기다리기를
 * 반복하면 네트워크 왕복이 직렬화되어 처리량이 왕복 지연에 묶입니다.
 *
 * <p>순서는 유지됩니다. 프로듀서 멱등성이 켜져 있으면 같은 파티션의 레코드는 전송 순서대로
 * 기록되며, 파티션 키가 Aggregate ID이므로 하나의 Aggregate에 대한 이벤트 순서가 보장됩니다.
 * 근거: docs/05-technical-design.md §9
 */
@Component
class KafkaMessageBroker implements MessageBroker {

    /** 배치 전체에 대한 확인 대기 시간입니다. 건별이 아니라 배치 단위로 잡습니다. */
    private static final Duration BATCH_ACK_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;

    KafkaMessageBroker(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public List<SendOutcome> sendAll(String topic, List<OutboxMessage> messages) {
        // 보내기와 기다리기를 나눕니다. 전부 보낸 뒤에 전부 기다려야 왕복이 겹칩니다.
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(messages.size());
        List<SendOutcome> dispatchFailures = new ArrayList<>();
        for (OutboxMessage message : messages) {
            try {
                futures.add(kafkaTemplate.send(topic, message.partitionKey(), message.payload()));
            } catch (RuntimeException e) {
                // send()는 비동기지만 메타데이터를 못 받거나 버퍼가 가득 차면 그 자리에서 던집니다.
                // 이것이 배치 밖으로 나가면 라운드 전체가 롤백되고, 실패 횟수도 백오프도 기록되지
                // 않은 채 같은 배치를 계속 다시 시도하게 됩니다.
                futures.add(null);
                dispatchFailures.add(SendOutcome.failed(message.eventId(), describe(e)));
            }
        }

        long deadline = System.nanoTime() + BATCH_ACK_TIMEOUT.toNanos();
        List<SendOutcome> outcomes = new ArrayList<>(messages.size());

        for (int i = 0; i < messages.size(); i++) {
            OutboxMessage message = messages.get(i);
            CompletableFuture<SendResult<String, String>> future = futures.get(i);
            if (future == null) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining <= 0) {
                    throw new TimeoutException("batch acknowledgement timed out");
                }
                // 확인을 받은 뒤에야 성공으로 기록합니다. 기다리지 않으면 발행하지 않은 이벤트를
                // PUBLISHED로 표시하게 됩니다.
                future.get(remaining, TimeUnit.NANOSECONDS);
                outcomes.add(SendOutcome.acknowledged(message.eventId()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcomes.add(SendOutcome.failed(message.eventId(), "interrupted while publishing"));
            } catch (Exception e) {
                outcomes.add(SendOutcome.failed(message.eventId(), describe(e)));
            }
        }
        outcomes.addAll(dispatchFailures);
        return outcomes;
    }

    /**
     * {@code last_error}에 남길 한 줄입니다.
     *
     * <p>원인 예외의 타입을 쓰되, 원인에 메시지가 없으면 감싼 예외의 메시지를 씁니다. Kafka는
     * 설명이 바깥에 있고 원인은 비어 있는 경우가 흔해서, 원인만 쓰면 "TimeoutException: null"만
     * 남습니다. 그 줄로는 운영자가 아무것도 알 수 없습니다.
     */
    private static String describe(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage() != null ? cause.getMessage() : e.getMessage();
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
