package io.parity.pay.support;

import io.parity.mockpg.MockPgApplication;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 통합 테스트에서 외부 PG를 실제로 띄웁니다.
 *
 * <p>목이 아니라 앱입니다. 같은 JVM 안이지만 별도 Spring 컨텍스트이고 호출은 HTTP로 나갑니다.
 * 그래서 읽기 타임아웃과 연결 오류가 흉내가 아니라 실제로 일어납니다.
 *
 * <p>테스트 클래스마다 띄우지 않고 한 번만 띄웁니다. 매번 띄우면 포트가 바뀌고, 캐시된 pay-api
 * 컨텍스트가 옛 포트를 계속 가리킵니다.
 *
 * <p>여기서 재현되지 않는 것도 분명히 해 둡니다. 같은 JVM이므로 **프로세스 강제 종료와 OS 수준의
 * 연결 거부**는 로컬·실험 환경(docker compose)에서 확인합니다.
 */
final class MockPgProcess {

    private static ConfigurableApplicationContext context;
    private static int port;

    private MockPgProcess() {}

    static synchronized int start(String jdbcUrl, String username, String password) {
        if (context != null) {
            return port;
        }
        // 기관이 실제로 읽는 자리로 넘깁니다. `properties(...)`는 **기본값**이라 앱의
        // application.yml보다 우선순위가 낮습니다. `spring.datasource.url`을 직접 넣으면 yml의
        // 기본 URL(로컬 5432)이 이기고, 기관은 엉뚱한 DB에 붙습니다.
        Map<String, Object> properties = new LinkedHashMap<>();
        // 기관은 자기 설정 파일만 읽습니다. 테스트에서 두 앱이 클래스패스를 공유하므로, 이것이
        // 없으면 기관이 pay-api의 application.yml을 읽고 우리 DB와 Flyway를 가져갑니다.
        properties.put("spring.config.name", "mock-pg");
        properties.put("MOCK_PG_PORT", 0);
        properties.put("MOCK_PG_DB_URL", jdbcUrl);
        properties.put("MOCK_PG_DB_USERNAME", username);
        properties.put("MOCK_PG_DB_PASSWORD", password);
        properties.put("spring.main.banner-mode", "off");
        properties.put("management.endpoints.web.exposure.include", "");

        context = new SpringApplicationBuilder(MockPgApplication.class)
                // 기관은 인증이 없습니다. 시험에서만 클래스패스가 겹쳐 401이 되므로 열어 둡니다.
                .sources(io.parity.institution.InstitutionSecurityBypass.class)
                .properties(properties)
                .run();
        port = context.getEnvironment().getProperty("local.server.port", Integer.class, 0);
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        return port;
    }
}
