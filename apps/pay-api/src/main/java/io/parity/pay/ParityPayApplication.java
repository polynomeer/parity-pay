package io.parity.pay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ParityPay API 진입점.
 *
 * <p>MVP에서는 도메인 모듈을 이 애플리케이션 안에 함께 배포합니다.
 * 근거: docs/05-technical-design.md §4, ADR-001
 */
@SpringBootApplication
@ConfigurationPropertiesScan("io.parity.pay")
public class ParityPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParityPayApplication.class, args);
    }
}
