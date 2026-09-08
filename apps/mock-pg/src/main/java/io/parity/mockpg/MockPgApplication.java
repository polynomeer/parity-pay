package io.parity.mockpg;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Mock PG — 카드 결제 대행사 대역.
 *
 * <p>Mock Bank와 **다른 프로세스**입니다. 다른 회사이기 때문이기도 하고, 하나를 죽여도 다른 하나는
 * 살아 있어야 실험이 되기 때문이기도 합니다. 카드 승인은 되는데 은행 출금이 안 되는 상황, 혹은 그
 * 반대는 실제로 일어나며 대응이 다릅니다.
 *
 * <p>근거: docs/05-technical-design.md §10, docs/13-implementation-checklist.md
 */
@SpringBootApplication
public class MockPgApplication {

    /**
     * 설정 파일 이름을 {@code mock-pg}으로 고정합니다.
     *
     * <p>통합 테스트에서는 pay-api와 클래스패스를 공유하므로, 기본 이름을 쓰면 우리 쪽
     * application.yml을 읽고 DB 설정과 Flyway까지 가져갑니다.
     */
    public static void main(String[] args) {
        new SpringApplicationBuilder(MockPgApplication.class)
                .properties("spring.config.name=mock-pg")
                .run(args);
    }
}
