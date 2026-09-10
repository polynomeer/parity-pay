package io.parity.pay.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.observability.InvariantMetrics;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 불변조건 현황 API (FE-M6).
 *
 * <p>이 값들은 Prometheus 지표로만 나가고 있었습니다. 운영 콘솔의 불변조건 모니터가 읽을 수 있게
 * 같은 캐시를 JSON으로도 엽니다.
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class InvariantApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InvariantMetrics invariantMetrics;

    @Autowired
    private io.parity.pay.api.security.OperatorBootstrap operatorBootstrap;

    private String operatorToken;

    @BeforeEach
    void setUp() {
        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
    }

    @Test
    @DisplayName("계산된 뒤에는 불변조건별 위반 건수를 돌려준다")
    void returnsValuesAfterRefresh() {
        invariantMetrics.refresh();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/invariants", HttpMethod.GET, new HttpEntity<>(ApiAuth.bearer(operatorToken)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> values = (List<?>) response.getBody().get("values");
        assertThat(values).isNotEmpty();
        assertThat(values).allSatisfy(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            assertThat((String) row.get("name")).startsWith("paritypay.");
            // 설명이 없으면 화면이 이름만 보여 주게 됩니다. 운영자가 읽을 수 없습니다.
            assertThat((String) row.get("description")).isNotBlank();
        });
        // 화면이 캐시가 멈춘 것을 알아볼 수 있어야 합니다.
        assertThat(response.getBody().get("refreshedAt")).isNotNull();
        assertThat(response.getBody().get("ageSeconds")).isNotNull();
    }

    @Test
    @DisplayName("불변조건은 위반 0건이어야 한다")
    void invariantsHoldOnACleanSystem() {
        invariantMetrics.refresh();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/invariants", HttpMethod.GET, new HttpEntity<>(ApiAuth.bearer(operatorToken)), Map.class);

        List<?> values = (List<?>) response.getBody().get("values");
        assertThat(values).allSatisfy(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            String name = (String) row.get("name");
            if (name.startsWith("paritypay.invariant.")) {
                assertThat(((Number) row.get("value")).longValue())
                        .as("불변조건 %s", name)
                        .isZero();
            }
        });
    }

    @Test
    @DisplayName("운영자 권한이 없으면 볼 수 없다")
    void requiresOperatorRole() {
        ApiAuth.Session customer =
                ApiAuth.registerAndLogin(restTemplate, "invariant-customer@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/invariants",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(customer.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("확인하지 못한 값을 0으로 채우지 않는다")
    void neverFakesZero() {
        // 확인하지 못한 것과 위반이 0건인 것은 다릅니다. 0으로 채우면 화면이 "정상"이라고
        // 말하는데 사실은 아무것도 확인하지 않은 상태가 됩니다.
        InvariantMetrics.InvariantSnapshot snapshot = invariantMetrics.snapshot();

        assertThat(snapshot.values()).allSatisfy(value -> {
            if (value.value() == null) {
                assertThat(snapshot.refreshedAt()).isNull();
            }
        });
    }
}
