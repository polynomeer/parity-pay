package io.parity.pay.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간은 주입받아 사용합니다. {@code Instant.now()}를 직접 호출하는 코드는 테스트에서 시각을 고정할 수
 * 없습니다. 서버 시간은 UTC입니다.
 *
 * <p>근거: docs/04-payment-policy.md BR-002, docs/10-test-strategy.md §10
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
