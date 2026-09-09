package io.parity.pay.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F-010 스냅샷 손상 후 원장 재구축.
 *
 * <p>지금까지 있던 것은 탐지뿐이었습니다. 스냅샷과 원장이 다르다는 것을 API와 지표로 볼 수는
 * 있었지만, 그다음에 무엇을 하는지는 문서에만 있고 실행해 본 적이 없었습니다.
 *
 * <p>여기서 손상을 직접 만들고 복구까지 갑니다. 손상은 SQL로 스냅샷을 고쳐 만듭니다. 운영 코드에는
 * 스냅샷을 임의로 바꾸는 경로가 없기 때문입니다(그것이 규칙입니다). 확인하는 것은 세 가지입니다.
 *
 * <ul>
 *   <li>탐지되는가 — 검증 API와 `paritypay.invariant.balance_snapshot_drift` 지표
 *   <li>복구되는가 — 운영자 API로 스냅샷이 원장 값으로 돌아오는가
 *   <li>원장은 그대로인가 — 복구가 원장을 건드리지 않는가(INV-006)
 * </ul>
 *
 * <p>근거: INV-010, ADR-008, docs/10-test-strategy.md §6, reports/11 F-010
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 4는 TestRestTemplate 빈을 자동으로 만들지 않습니다. 3.5에서는 RANDOM_PORT만으로
// 주입됐습니다. 기반 클래스에 두면 웹 서버가 없는 시험까지 깨지므로 여기에 붙입니다.
@AutoConfigureTestRestTemplate
class BalanceSnapshotRebuildTest extends AbstractIntegrationTest {

    private static final long TOP_UP_AMOUNT = 100_000L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private io.parity.pay.api.observability.InvariantMetrics invariantMetrics;

    private UUID walletId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, audit_log,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, top_up, outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();

        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "rebuild-buyer@example.com", "password1234");
        walletId = session.walletId();
        accessToken = session.accessToken();

        ResponseEntity<Map> bankAccount = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("bankCode", "004", "accountNumber", "110-9876-5432", "initialBalance", 1_000_000),
                        ApiAuth.bearer(accessToken)),
                Map.class);
        UUID bankAccountId = UUID.fromString((String) bankAccount.getBody().get("bankAccountId"));

        ResponseEntity<Map> topUp = restTemplate.exchange(
                "/api/v1/top-ups",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "walletId",
                                walletId.toString(),
                                "bankAccountId",
                                bankAccountId.toString(),
                                "amount",
                                TOP_UP_AMOUNT,
                                "currency",
                                "KRW"),
                        ApiAuth.bearer(accessToken, "rebuild-topup-1")),
                Map.class);
        assertThat(topUp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("F-010: 손상된 스냅샷은 탐지되고, 원장으로 재구축되며, 원장은 변하지 않는다")
    void corruptedSnapshotIsDetectedAndRebuiltFromTheLedger() {
        LedgerFingerprint before = ledgerFingerprint();
        assertThat(verification().get("matches")).isEqualTo(true);
        assertThat(driftCount()).isZero();

        // 손상 주입. 스냅샷만 바꾸고 원장은 그대로 둡니다.
        jdbcTemplate.update("UPDATE wallet_balance SET available_amount = ? WHERE wallet_id = ?", 1L, walletId);

        // 1. 탐지
        Map<String, Object> detected = verification();
        assertThat(detected.get("matches")).isEqualTo(false);
        assertThat(((Number) detected.get("snapshotBalance")).longValue()).isEqualTo(1L);
        assertThat(((Number) detected.get("ledgerBalance")).longValue()).isEqualTo(TOP_UP_AMOUNT);
        assertThat(driftCount()).isEqualTo(1L);

        // 2. 재구축
        ResponseEntity<Map> rebuild = rebuild(ApiAuth.OPS_APPROVER, "F-010 실험: 손상된 스냅샷 복구");

        assertThat(rebuild.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rebuild.getBody().get("status")).isEqualTo("REBUILT");
        assertThat(((Number) rebuild.getBody().get("snapshotBefore")).longValue())
                .isEqualTo(1L);
        assertThat(((Number) rebuild.getBody().get("snapshotAfter")).longValue())
                .isEqualTo(TOP_UP_AMOUNT);

        // 3. 복구 확인
        assertThat(verification().get("matches")).isEqualTo(true);
        assertThat(driftCount()).isZero();
        assertThat(availableAmount()).isEqualTo(TOP_UP_AMOUNT);

        // 4. 원장은 손대지 않았습니다. 재구축이 원장을 "맞춰버리면" 진실이 사라집니다.
        assertThat(ledgerFingerprint()).isEqualTo(before);

        // 5. 무엇을 왜 했는지 남습니다.
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor, action, before_state, after_state, result, reason, detail FROM audit_log"
                        + " WHERE action = 'WALLET_BALANCE_REBUILD'");
        assertThat(audit.get("actor")).isEqualTo(ApiAuth.OPS_OPERATOR);
        assertThat(audit.get("before_state")).isEqualTo("snapshot=1 ledger=" + TOP_UP_AMOUNT);
        assertThat(audit.get("after_state")).isEqualTo("snapshot=" + TOP_UP_AMOUNT);
        assertThat(audit.get("result")).isEqualTo("SUCCEEDED");
        assertThat((String) audit.get("detail")).contains(ApiAuth.OPS_APPROVER);
    }

    @Test
    @DisplayName("어긋난 지갑이 목록으로 나온다 — 지표는 개수만 알려주므로")
    void driftedWalletsAreListedForOperators() {
        jdbcTemplate.update("UPDATE wallet_balance SET available_amount = ? WHERE wallet_id = ?", 1L, walletId);

        ResponseEntity<List> response = restTemplate.exchange(
                "/api/v1/admin/wallets/balance-drift",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD))),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = response.getBody();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("walletId")).isEqualTo(walletId.toString());
        // 부호가 있어야 어느 쪽이 큰지 그대로 드러납니다. 여기서는 스냅샷이 원장보다 작습니다.
        assertThat(((Number) rows.get(0).get("difference")).longValue()).isNegative();
        assertThat(((Number) rows.get(0).get("ledgerBalance")).longValue())
                .isGreaterThan(((Number) rows.get(0).get("snapshotTotal")).longValue());

        // 목록은 고치지 않습니다. 재구축은 지갑마다 사유와 승인자를 받아 따로 실행합니다.
        assertThat(availableAmount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 일치하는 스냅샷은 다시 쓰지 않는다")
    void consistentSnapshotIsLeftAlone() {
        long versionBefore = snapshotVersion();

        ResponseEntity<Map> rebuild = rebuild(ApiAuth.OPS_APPROVER, "정상 상태 확인");

        assertThat(rebuild.getBody().get("status")).isEqualTo("ALREADY_CONSISTENT");
        assertThat(snapshotVersion()).isEqualTo(versionBefore);
        // 변화가 없어도 시도는 기록합니다.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT result FROM audit_log WHERE action = 'WALLET_BALANCE_REBUILD'", String.class))
                .isEqualTo("NO_CHANGE");
    }

    @Test
    @DisplayName("원장이 처리중 금액보다 적으면 맞추지 않고 거부한다")
    void rebuildIsRefusedWhenItWouldCreateANegativeBalance() {
        // 처리중 금액이 원장 잔액보다 큰 상태입니다. 스냅샷만의 문제가 아니므로 사람이 봐야 합니다.
        jdbcTemplate.update(
                "UPDATE wallet_balance SET available_amount = 0, pending_amount = ? WHERE wallet_id = ?",
                TOP_UP_AMOUNT * 2,
                walletId);

        ResponseEntity<Map> rebuild = rebuild(ApiAuth.OPS_APPROVER, "거부 경로 확인");

        assertThat(rebuild.getBody().get("status")).isEqualTo("REFUSED");
        assertThat((String) rebuild.getBody().get("detail")).contains("negative");
        // 거부했으면 아무것도 바뀌지 않아야 합니다.
        assertThat(availableAmount()).isZero();
        assertThat(pendingAmount()).isEqualTo(TOP_UP_AMOUNT * 2);
    }

    @Test
    @DisplayName("승인자가 요청자와 같으면 재구축할 수 없다")
    void selfApprovalIsRejected() {
        jdbcTemplate.update("UPDATE wallet_balance SET available_amount = 1 WHERE wallet_id = ?", walletId);

        ResponseEntity<Map> rebuild = rebuild(ApiAuth.OPS_OPERATOR, "혼자 승인 시도");

        // 보정 API와 같은 응답입니다. 승인자 지정은 요청의 문제이므로 400입니다.
        assertThat(rebuild.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rebuild.getBody().get("code")).isEqualTo("INVALID_REQUEST");
        assertThat(availableAmount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("승인 권한이 없는 사람을 승인자로 적으면 거부한다")
    void approverWithoutAuthorityIsRejected() {
        jdbcTemplate.update("UPDATE wallet_balance SET available_amount = 1 WHERE wallet_id = ?", walletId);

        ResponseEntity<Map> rebuild = rebuild(ApiAuth.OPS_VIEWER, "권한 없는 승인자");

        assertThat(rebuild.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(availableAmount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("조회 권한만 있는 운영자는 재구축할 수 없다")
    void viewerCannotRebuild() {
        String viewerToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
        HttpHeaders headers = ApiAuth.bearer(viewerToken);
        headers.set("X-Approver-Id", ApiAuth.OPS_APPROVER);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/wallets/" + walletId + "/balance-rebuild",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "권한 없음 확인"), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<Map> rebuild(String approver, String reason) {
        String operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
        HttpHeaders headers = ApiAuth.bearer(operatorToken);
        headers.set("X-Approver-Id", approver);
        return restTemplate.exchange(
                "/api/v1/admin/wallets/" + walletId + "/balance-rebuild",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", reason), headers),
                Map.class);
    }

    /** 검증 조회는 운영자 권한입니다. 회원 토큰으로는 403입니다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> verification() {
        String viewerToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
        return restTemplate
                .exchange(
                        "/api/v1/wallets/" + walletId + "/ledger-verification",
                        HttpMethod.GET,
                        new HttpEntity<>(ApiAuth.bearer(viewerToken)),
                        Map.class)
                .getBody();
    }

    /**
     * 지표는 주기적으로 계산해 캐시합니다. 시험은 그 주기를 기다리는 대신 직접 갱신합니다.
     *
     * <p>읽을 때마다 계산하던 때는 이 호출이 필요 없었습니다. 스크레이프마다 원장 전체를 집계하는
     * 비용 때문에 바꿨습니다. 근거: reports/11 M-006
     */
    private long driftCount() {
        invariantMetrics.refresh();
        return (long) meterRegistry
                .get("paritypay.invariant.balance_snapshot_drift")
                .gauge()
                .value();
    }

    /** 원장이 바뀌지 않았음을 보이기 위한 지문입니다. 거래 수, 항목 수, 차변·대변 합계. */
    private LedgerFingerprint ledgerFingerprint() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT (SELECT count(*) FROM ledger_transaction) AS transactions,
                       (SELECT count(*) FROM ledger_entry) AS entries,
                       (SELECT coalesce(sum(amount), 0)::bigint FROM ledger_entry
                         WHERE direction = 'DEBIT') AS debit_total,
                       (SELECT coalesce(sum(amount), 0)::bigint FROM ledger_entry
                         WHERE direction = 'CREDIT') AS credit_total
                """);
        return new LedgerFingerprint(
                ((Number) row.get("transactions")).longValue(),
                ((Number) row.get("entries")).longValue(),
                ((Number) row.get("debit_total")).longValue(),
                ((Number) row.get("credit_total")).longValue());
    }

    private long availableAmount() {
        return column("available_amount");
    }

    private long pendingAmount() {
        return column("pending_amount");
    }

    private long snapshotVersion() {
        return column("version");
    }

    private long column(String name) {
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT " + name + " FROM wallet_balance WHERE wallet_id = ?", Long.class, walletId);
        return values.get(0);
    }

    private record LedgerFingerprint(long transactions, long entries, long debitTotal, long creditTotal) {}
}
