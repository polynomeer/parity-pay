package io.parity.pay.api.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 발행기 설정.
 *
 * <p>{@code enabled}는 테스트에서 발행 시점을 직접 통제하기 위한 스위치입니다. 운영에서는 켭니다.
 *
 * <p>{@code validateSchema}는 기록 시점에 이벤트를 JSON Schema로 검사할지입니다. 계약은 CI의
 * 테스트가 지키므로 운영에서는 끕니다. 켜 두면 스키마가 틀렸을 때 결제가 실패하는데, 그 위험을
 * 운영에서 질 이유가 없습니다. 테스트와 로컬에서는 켭니다.
 *
 * <p>{@code maxRoundsPerPoll}은 한 번 깨어났을 때 이어서 비울 수 있는 배치 수입니다. 이것이 없으면
 * 폴링 간격이 그대로 처리량 상한이 됩니다(0.5초 간격 × 100건 = 200건/초).
 */
@ConfigurationProperties(prefix = "paritypay.events")
public record OutboxProperties(
        String topic,
        boolean publisherEnabled,
        int batchSize,
        int maxRoundsPerPoll,
        int maxAttempts,
        boolean validateSchema,
        Duration baseBackoff,
        Duration maxBackoff) {

    public OutboxProperties {
        topic = topic == null ? "paritypay.events" : topic;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        maxRoundsPerPoll = maxRoundsPerPoll <= 0 ? 20 : maxRoundsPerPoll;
        maxAttempts = maxAttempts <= 0 ? 10 : maxAttempts;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(1) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
    }
}
