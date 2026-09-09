package io.parity.pay.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.api.observability.InvariantMetrics;
import io.parity.pay.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 불변조건 지표의 계산 시점.
 *
 * <p>게이지를 읽을 때마다 질의하면 스크레이프마다 원장 전체를 집계하게 됩니다. 항목 200만 건에서
 * INV-001 검사 3.6초, INV-010 검사 0.87초였고 스크레이프 간격은 5초입니다(M-006). 그래서 값을
 * 주기적으로 계산해 캐시합니다.
 *
 * <p>캐시는 조용한 실패를 만들 수 있으므로 마지막 갱신 이후 시간을 함께 내보냅니다. 이 시험은 두
 * 가지를 지킵니다 — 읽기가 DB를 건드리지 않는다는 것과, 캐시가 늙었을 때 그 사실이 드러난다는 것.
 *
 * <p>근거: reports/11 M-006, ADR-008
 */
class InvariantMetricsTest extends AbstractIntegrationTest {

    @Autowired
    private InvariantMetrics invariantMetrics;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE ledger_entry, ledger_transaction, ledger_account,"
                + " wallet_balance, wallet, member CASCADE");
        invariantMetrics.refresh();
    }

    @Test
    @DisplayName("지표를 읽는 것만으로는 다시 계산되지 않는다")
    void readingTheGaugeDoesNotQueryTheDatabase() {
        assertThat(gauge("paritypay.invariant.balance_snapshot_drift")).isZero();

        // 위반을 만들어 둡니다. 읽을 때마다 계산하던 때는 이 값이 곧바로 1이 됐습니다.
        insertSnapshotDrift();

        assertThat(gauge("paritypay.invariant.balance_snapshot_drift")).isZero();

        invariantMetrics.refresh();

        assertThat(gauge("paritypay.invariant.balance_snapshot_drift")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("갱신이 실패해도 마지막 값을 0으로 덮지 않는다")
    void aFailedRefreshKeepsTheLastValue() {
        insertSnapshotDrift();
        invariantMetrics.refresh();
        assertThat(gauge("paritypay.invariant.balance_snapshot_drift")).isEqualTo(1.0d);

        // 질의가 실패하는 상황을 만듭니다. 값을 0으로 덮으면 위반이 사라진 것처럼 보입니다.
        jdbcTemplate.execute("ALTER TABLE wallet_balance RENAME TO wallet_balance_hidden");
        try {
            invariantMetrics.refresh();
            assertThat(gauge("paritypay.invariant.balance_snapshot_drift")).isEqualTo(1.0d);
        } finally {
            jdbcTemplate.execute("ALTER TABLE wallet_balance_hidden RENAME TO wallet_balance");
        }
    }

    @Test
    @DisplayName("캐시가 늙으면 나이로 드러난다")
    void staleCacheIsVisible() {
        invariantMetrics.refresh();

        // 갱신 직후에는 나이가 0에 가깝습니다.
        assertThat(gauge("paritypay.invariant.refresh_age_seconds")).isLessThan(60.0d);
        // 한 바퀴에 걸린 시간도 함께 나옵니다. 원장이 커지면 이 값이 자라는 것을 보고 주기를 조정합니다.
        assertThat(gauge("paritypay.invariant.refresh_duration_seconds")).isGreaterThanOrEqualTo(0.0d);
    }

    /** 스냅샷은 1,000원이라 말하는데 원장에는 항목이 없는 지갑을 만듭니다. INV-010 위반입니다. */
    private void insertSnapshotDrift() {
        jdbcTemplate.update(
                """
                INSERT INTO member (member_id, email, password_hash, status, created_at)
                VALUES (gen_random_uuid(), 'metrics@example.com', 'x', 'ACTIVE', now())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO wallet (wallet_id, member_id, currency, status, created_at)
                SELECT gen_random_uuid(), member_id, 'KRW', 'ACTIVE', now() FROM member LIMIT 1
                """);
        jdbcTemplate.update(
                """
                INSERT INTO wallet_balance (wallet_id, available_amount, pending_amount, version, updated_at)
                SELECT wallet_id, 1000, 0, 0, now() FROM wallet LIMIT 1
                """);
        // 원장 계정은 있고 항목은 없습니다. 재생값 0과 스냅샷 1,000이 어긋납니다.
        jdbcTemplate.update(
                """
                INSERT INTO ledger_account
                    (account_id, account_code, owner_type, owner_id, currency, status, created_at)
                SELECT gen_random_uuid(), '2010', 'WALLET', wallet_id, 'KRW', 'ACTIVE', now()
                  FROM wallet LIMIT 1
                """);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }
}
