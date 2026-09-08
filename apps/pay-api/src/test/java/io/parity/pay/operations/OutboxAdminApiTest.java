package io.parity.pay.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 운영자 Outbox 조회·재처리 API.
 *
 * <p>발행을 포기한 이벤트는 {@code FAILED}로 남고 발행기가 더 이상 집어가지 않습니다. 적체 지표는
 * 그 숫자를 알려 주지만, 지금까지 그 숫자를 다룰 방법은 SQL뿐이었습니다.
 *
 * <p>근거: docs/13 Phase 3, docs/09-consistency-recovery.md §5
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxAdminApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    private String operatorToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE outbox_event, audit_log CASCADE");
        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
        viewerToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
    }

    @Test
    @DisplayName("발행을 포기한 이벤트를 운영자가 목록으로 본다")
    void failedEventsAreVisibleToOperators() {
        UUID eventId = seedEvent("FAILED", "wallet-1", Instant.now(), 10, "broker is unavailable");

        ResponseEntity<List> response = restTemplate.exchange(
                "/api/v1/admin/outbox-events?status=FAILED",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(operatorToken)),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = response.getBody();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("eventId")).isEqualTo(eventId.toString());
        assertThat(rows.get(0).get("attemptCount")).isEqualTo(10);
        assertThat(rows.get(0).get("lastError")).isEqualTo("broker is unavailable");
        // 목록에 업무 데이터를 늘어놓지 않습니다.
        assertThat(rows.get(0)).doesNotContainKey("payload");
    }

    @Test
    @DisplayName("재처리하면 시도 횟수가 초기화되어 다시 발행 대상이 되고 감사 로그가 남는다")
    void requeueReturnsTheEventToThePublisher() {
        UUID eventId = seedEvent("FAILED", "wallet-1", Instant.now(), 10, "broker is unavailable");

        ResponseEntity<Map> response = retry(eventId, operatorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat(response.getBody().get("changed")).isEqualTo(true);
        assertThat(response.getBody().get("previousAttemptCount")).isEqualTo(10);
        assertThat(response.getBody().get("laterSiblingPublished")).isEqualTo(false);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, attempt_count, last_error FROM outbox_event WHERE event_id = ?", eventId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        // 그대로 두면 한 번 시도하고 곧바로 다시 FAILED가 됩니다.
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("last_error")).isNull();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'OUTBOX_EVENT_REQUEUE' AND resource_id = ?",
                        Long.class,
                        eventId.toString()))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 발행된 이벤트는 되돌릴 수 없다")
    void publishedEventsCannotBeRequeued() {
        UUID eventId = seedEvent("PUBLISHED", "wallet-1", Instant.now(), 1, null);

        ResponseEntity<Map> response = retry(eventId, operatorToken);

        assertThat(response.getBody().get("changed")).isEqualTo(false);
        assertThat(response.getBody().get("status")).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("같은 Aggregate의 뒤 이벤트가 이미 나갔으면 순서가 뒤집힌다고 알려 준다")
    void requeueWarnsWhenALaterSiblingIsAlreadyPublished() {
        Instant earlier = Instant.now().minusSeconds(60);
        UUID failed = seedEvent("FAILED", "wallet-1", earlier, 10, "broker is unavailable");
        seedEvent("PUBLISHED", "wallet-1", earlier.plusSeconds(30), 1, null);

        ResponseEntity<Map> response = retry(failed, operatorToken);

        assertThat(response.getBody().get("changed")).isEqualTo(true);
        // 막지 않습니다. 되돌리지 않으면 이벤트가 영영 나가지 않으므로, 어느 쪽이 나은지는
        // 이벤트 종류에 달렸습니다. 사실만 알려 줍니다. 근거: reports/11 M-001
        assertThat(response.getBody().get("laterSiblingPublished")).isEqualTo(true);
        assertThat((String) response.getBody().get("detail")).contains("out of order");
    }

    @Test
    @DisplayName("조회 권한만 있는 운영자는 재처리할 수 없다")
    void viewersCanLookButNotRequeue() {
        UUID eventId = seedEvent("FAILED", "wallet-1", Instant.now(), 10, "broker is unavailable");

        ResponseEntity<List> list = restTemplate.exchange(
                "/api/v1/admin/outbox-events?status=FAILED",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(viewerToken)),
                List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> retry = retry(eventId, viewerToken);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId))
                .isEqualTo("FAILED");
    }

    private ResponseEntity<Map> retry(UUID eventId, String token) {
        return restTemplate.exchange(
                "/api/v1/admin/outbox-events/" + eventId + "/retry",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "broker recovered"), ApiAuth.bearer(token)),
                Map.class);
    }

    private UUID seedEvent(String status, String partitionKey, Instant occurredAt, int attempts, String lastError) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_event
                    (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
                     payload, trace_id, status, attempt_count, next_attempt_at, last_error,
                     occurred_at, created_at, published_at)
                VALUES (?, 'WalletCreated', 1, 'Wallet', ?, ?, '{}'::jsonb, NULL, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                partitionKey,
                partitionKey,
                status,
                attempts,
                Timestamp.from(occurredAt),
                lastError,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                "PUBLISHED".equals(status) ? Timestamp.from(occurredAt) : null);
        return eventId;
    }
}
