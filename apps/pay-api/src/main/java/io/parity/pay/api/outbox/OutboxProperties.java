package io.parity.pay.api.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 발행기 설정.
 *
 * <p>{@code enabled}는 테스트에서 발행 시점을 직접 통제하기 위한 스위치입니다. 운영에서는 켭니다.
 */
@ConfigurationProperties(prefix = "paritypay.events")
public record OutboxProperties(
        String topic,
        boolean publisherEnabled,
        int batchSize,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff) {

    public OutboxProperties {
        topic = topic == null ? "paritypay.events" : topic;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        maxAttempts = maxAttempts <= 0 ? 10 : maxAttempts;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(1) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
    }
}
