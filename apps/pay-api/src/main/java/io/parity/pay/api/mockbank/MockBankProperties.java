package io.parity.pay.api.mockbank;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 기관(Mock Bank) 연결 설정.
 *
 * <p>타임아웃이 이 설정의 핵심입니다. 읽기 타임아웃이 없으면 기관이 응답하지 않을 때 우리 스레드가
 * 무한정 묶이고, 그동안 커넥션 풀도 함께 묶입니다. 타임아웃이 발생하면 실패가 아니라 결과를 모르는
 * 것으로 다루므로(ADR-007), 짧게 잡는 편이 안전합니다.
 */
@ConfigurationProperties(prefix = "paritypay.mock-bank")
public record MockBankProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public MockBankProperties {
        baseUrl = baseUrl == null ? "http://localhost:8090" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
