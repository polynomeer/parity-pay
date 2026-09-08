package io.parity.pay.api.mockpg;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 PG 연결 설정.
 *
 * <p>은행과 값을 따로 둡니다. 카드 승인과 은행 이체는 응답 특성이 다르고, 한쪽이 느려졌다고 다른
 * 쪽의 타임아웃까지 늘릴 이유가 없습니다.
 */
@ConfigurationProperties(prefix = "paritypay.mock-pg")
public record MockPgProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public MockPgProperties {
        baseUrl = baseUrl == null ? "http://localhost:8091" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
