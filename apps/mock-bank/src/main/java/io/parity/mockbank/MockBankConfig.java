package io.parity.mockbank;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 이 앱이 직접 만드는 빈들입니다. */
@Configuration
class MockBankConfig {

    /** 기관의 시계입니다. 우리 시계와 같을 이유가 없지만, 대역이므로 시스템 시계를 씁니다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
