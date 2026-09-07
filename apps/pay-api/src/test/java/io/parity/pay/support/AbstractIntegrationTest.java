package io.parity.pay.support;

import io.parity.pay.ParityPayApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
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
}
