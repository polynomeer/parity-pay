package io.parity.pay.support;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.mockpg.MockPgBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * 통합 테스트 기반 클래스.
 *
 * <p>실제 PostgreSQL과 Redpanda를 컨테이너로 띄웁니다. 인메모리 DB나 임베디드 브로커로 대체하면
 * 제약조건·트리거·격리수준과 at-least-once 전달을 검증할 수 없습니다.
 * 근거: docs/10-test-strategy.md §5, CLAUDE.md §6
 *
 * <p>컨테이너는 JUnit의 {@code @Testcontainers} 확장 대신 싱글턴으로 직접 띄웁니다. 확장을 쓰면
 * 테스트 클래스마다 컨테이너가 멈췄다 다시 시작되면서 포트가 바뀌고, 캐시된 Spring 컨텍스트의
 * 커넥션이 끊어집니다.
 */
@SpringBootTest(classes = ParityPayApplication.class)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("paritypay")
            .withUsername("paritypay")
            .withPassword("paritypay");

    @ServiceConnection
    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v24.3.6");

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    /**
     * 외부 기관을 띄우고 그 주소를 알려 줍니다.
     *
     * <p>기관은 pay-api의 Flyway가 만든 표를 씁니다. 기관이 우리와 DB를 공유하는 것은 현실과
     * 다르지만, 이 단계의 목적은 호출 경계를 진짜 네트워크로 만드는 것입니다.
     */
    @Autowired
    private MockBankBehavior externalBank;

    @Autowired
    private MockPgBehavior externalPg;

    /**
     * 외부 기관의 상태를 되돌립니다.
     *
     * <p>기관이 같은 프로세스의 빈이었을 때는 컨텍스트마다 스위치가 따로였습니다. 지금은 **모든
     * 테스트가 하나의 기관을 공유**하므로, 한 테스트가 켜 둔 장애 모드가 다음 테스트로 넘어갑니다.
     * 실제 외부 기관도 그렇습니다 — 우리 테스트가 끝났다고 기관이 초기화되지 않습니다.
     *
     * <p>하위 클래스의 {@code @BeforeEach}는 이 뒤에 돌므로, 시나리오가 필요로 하는 모드는 그대로
     * 유지됩니다.
     */
    @BeforeEach
    void resetExternalInstitutions() {
        externalBank.reset();
        externalPg.reset();
    }

    @DynamicPropertySource
    static void externalInstitutions(DynamicPropertyRegistry registry) {
        registry.add(
                "paritypay.mock-bank.base-url",
                () -> "http://localhost:"
                        + MockBankProcess.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        registry.add(
                "paritypay.mock-pg.base-url",
                () -> "http://localhost:"
                        + MockPgProcess.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
