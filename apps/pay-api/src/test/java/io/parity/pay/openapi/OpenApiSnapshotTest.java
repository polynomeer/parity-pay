package io.parity.pay.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OpenAPI 명세와 구현의 일치 검사.
 *
 * <p>명세를 손으로 관리하면 구현이 앞서 나가도 아무도 모릅니다. 여기서는 실행 중인 애플리케이션이
 * 만들어낸 명세를 저장소의 스냅샷과 비교합니다. API를 바꾸면 이 테스트가 실패하고, 스냅샷을
 * 갱신하는 것이 곧 "문서를 고쳤다"는 뜻이 됩니다.
 *
 * <p>스냅샷 갱신: {@code ./gradlew :apps:pay-api:test -PupdateOpenApiSnapshot --tests "*OpenApiSnapshotTest"}
 *
 * <p>근거: docs/03-mvp-scope.md §7, docs/10-test-strategy.md §8·§11
 */
@SpringBootTest(
        classes = ParityPayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSnapshotTest extends AbstractIntegrationTest {

    private static final Path SNAPSHOT = Path.of("..", "..", "docs", "api", "openapi.json");
    private static final String UPDATE_FLAG = "updateOpenApiSnapshot";

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String operatorToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE refresh_token, login_attempt, member CASCADE");
        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
    }

    @Test
    @DisplayName("명세는 인증된 운영자만 볼 수 있다")
    void apiDocsRequireOperatorRole() {
        assertThat(restTemplate.getForEntity("/v3/api-docs/customer", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ApiAuth.Session customer =
                ApiAuth.registerAndLogin(restTemplate, "openapi-customer@example.com", "password1234");
        assertThat(fetch("/v3/api-docs/customer", customer.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("생성된 명세가 저장소 스냅샷과 일치한다")
    void generatedSpecMatchesTheCommittedSnapshot() throws IOException {
        String generated = normalize(fetchSpec("customer"), fetchSpec("operations"));

        if (System.getProperty(UPDATE_FLAG) != null || System.getenv("UPDATE_OPENAPI_SNAPSHOT") != null) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, generated);
            System.out.println("OpenAPI 스냅샷을 갱신했습니다: " + SNAPSHOT.toAbsolutePath().normalize());
            return;
        }

        assertThat(Files.exists(SNAPSHOT))
                .as("스냅샷이 없습니다. -DupdateOpenApiSnapshot 으로 생성하세요: %s", SNAPSHOT)
                .isTrue();

        assertThat(generated)
                .as(
                        """
                        OpenAPI 명세가 저장소 스냅샷과 다릅니다.
                        API를 의도적으로 바꿨다면 스냅샷을 갱신하고 함께 커밋하세요:
                          ./gradlew :apps:pay-api:test -PupdateOpenApiSnapshot --tests "*OpenApiSnapshotTest"
                        """)
                .isEqualTo(Files.readString(SNAPSHOT));
    }

    @Test
    @DisplayName("명세가 인증 방식과 멱등성 헤더를 설명한다")
    void specDocumentsAuthenticationAndIdempotency() throws IOException {
        JsonNode customer = objectMapper.readTree(fetchSpec("customer"));

        assertThat(customer.at("/components/securitySchemes/bearerAuth/scheme").asText())
                .isEqualTo("bearer");
        // 금융 쓰기 경로는 멱등 키를 헤더로 받습니다.
        assertThat(customer.at("/paths/~1api~1v1~1top-ups/post/parameters").toString())
                .contains("Idempotency-Key");
        assertThat(customer.at("/paths/~1api~1v1~1payments/post/parameters").toString())
                .contains("Idempotency-Key");
    }

    private String fetchSpec(String group) {
        ResponseEntity<String> response = fetch("/v3/api-docs/" + group, operatorToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> fetch(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * 그룹별 명세를 하나로 묶고 키 순서를 고정합니다.
     *
     * <p>생성된 JSON의 객체 키 순서는 실행마다 달라질 수 있습니다. 그대로 비교하면 내용이 같은데도
     * 매번 다르다고 나오므로, 트리를 일반 Map으로 바꿔 키 기준으로 정렬해 직렬화합니다. 배열
     * 순서는 유지합니다. enum 값처럼 순서 자체가 의미인 경우가 있기 때문입니다.
     */
    private String normalize(String customerSpec, String operationsSpec) throws IOException {
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("customer", toSortedStructure(objectMapper.readTree(customerSpec)));
        combined.put("operations", toSortedStructure(objectMapper.readTree(operationsSpec)));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(combined) + "\n";
    }

    private Object toSortedStructure(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.fields()
                    .forEachRemaining(entry ->
                            sorted.put(entry.getKey(), toSortedStructure(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(child -> values.add(toSortedStructure(child)));
            return values;
        }
        return objectMapper.convertValue(node, Object.class);
    }
}
