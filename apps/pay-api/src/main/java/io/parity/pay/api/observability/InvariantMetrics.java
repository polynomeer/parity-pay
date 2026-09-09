package io.parity.pay.api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 금융 불변조건 위반을 상시 감시하는 지표.
 *
 * <p>이 값들은 평소에 전부 0이어야 합니다. 0이 아니면 시스템이 스스로 규칙을 어긴 것이므로 한
 * 건이라도 즉시 경보 대상입니다. 성공률·지연 같은 지표는 임계치를 기준선에서 정해야 하지만, 이
 * 지표들의 임계치는 처음부터 0입니다.
 *
 * <p><b>값은 주기적으로 계산해 캐시합니다.</b> 처음에는 게이지를 읽을 때마다 질의했습니다. 그러면
 * 스크레이프마다 원장 전체를 집계하게 되고, 원장이 커질수록 비싸집니다 — 항목 200만 건에서 INV-001
 * 검사 3.6초, INV-010 검사 0.87초였습니다(M-006). 스크레이프 간격이 5초이므로 사실상 쉬지 않고
 * 도는 것이고, 더 커지면 스크레이프가 시간 초과되어 <b>모든</b> 지표가 사라집니다. 불변조건 감시가
 * 원장이 커질수록 약해지는 셈입니다.
 *
 * <p>캐시는 조용한 실패를 만들 수 있습니다. 갱신이 멈춘 채 마지막 0이 계속 보이면 아무 일도 없는
 * 것처럼 보입니다. 그래서 마지막 갱신 이후 시간을 함께 내보내고 경보를 겁니다. 첫 갱신 전에는
 * 무한대이므로 "아직 한 번도 재지 않았다"와 "재는 것을 멈췄다"가 같은 경보로 잡힙니다.
 *
 * <p>근거: docs/05-technical-design.md §12(경보), reports/11 M-006, INV-001·INV-003·INV-010
 */
@Component
public class InvariantMetrics {

    private static final Logger log = LoggerFactory.getLogger(InvariantMetrics.class);

    /** 원장 전체를 훑는 질의입니다. 나머지는 인덱스 조회라 비용이 작지만 함께 캐시합니다. */
    private static final List<Probe> PROBES = List.of(
            new Probe(
                    "paritypay.invariant.unbalanced_ledger_transactions",
                    "INV-001 위반: 차변과 대변이 다른 확정 원장 거래 수 (항상 0이어야 합니다)",
                    """
                    SELECT count(*)
                      FROM (
                          SELECT e.transaction_id
                            FROM ledger_entry e
                           GROUP BY e.transaction_id
                          HAVING coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END), 0)
                               <> coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END), 0)
                      ) unbalanced
                    """),
            new Probe(
                    "paritypay.invariant.negative_wallet_balances",
                    "INV-003 위반: 가용 잔액이 음수인 지갑 수 (항상 0이어야 합니다)",
                    "SELECT count(*) FROM wallet_balance WHERE available_amount < 0 OR pending_amount < 0"),
            new Probe(
                    "paritypay.invariant.balance_snapshot_drift",
                    "INV-010 위반: 잔액 스냅샷과 원장 재생값이 다른 지갑 수 (항상 0이어야 합니다)",
                    """
                    SELECT count(*)
                      FROM wallet_balance wb
                      JOIN ledger_account la
                        ON la.owner_id = wb.wallet_id AND la.account_code = '2010'
                     WHERE wb.available_amount + wb.pending_amount <> (
                           SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount
                                                    ELSE -e.amount END), 0)
                             FROM ledger_entry e
                            WHERE e.account_id = la.account_id)
                    """),
            new Probe(
                    "paritypay.invariant.over_cancelled_payments",
                    "INV-005 위반: 누적 취소액이 승인액을 넘은 결제 수 (항상 0이어야 합니다)",
                    """
                    SELECT count(*) FROM payment
                     WHERE completed_cancellation_amount + processing_cancellation_amount > approved_amount
                    """),
            // 운영 상태 지표입니다. 0이 아닌 것이 곧 사고는 아니지만 오래 쌓이면 사고입니다.
            new Probe(
                    "paritypay.topup.unknown",
                    "결과가 확정되지 않은 충전 수",
                    "SELECT count(*) FROM top_up WHERE status IN ('UNKNOWN', 'PROCESSING')"),
            new Probe(
                    "paritypay.recovery.manual_review",
                    "자동 복구를 포기하고 사람이 확인해야 하는 건수",
                    "SELECT count(*) FROM top_up_recovery WHERE requires_manual_review = true"),
            new Probe(
                    "paritypay.settlement.unsettled_payouts",
                    "지급 결과가 확정되지 않은 정산 수",
                    "SELECT count(*) FROM settlement WHERE status IN ('PAYING', 'UNKNOWN')"),
            new Probe(
                    "paritypay.reconciliation.open_mismatches",
                    "해결되지 않은 대사 불일치 수",
                    "SELECT count(*) FROM reconciliation_mismatch WHERE resolution_status = 'OPEN'"));

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Map<String, Double> values = new ConcurrentHashMap<>();

    private volatile Instant refreshedAt;
    // 초가 아니라 밀리초 정수로 들고 있습니다. 이 저장소는 필드에 부동소수점을 두지
    // 않습니다(ModuleBoundaryTest). 시간이 돈은 아니지만 규칙은 전면 금지입니다.
    private volatile long lastRefreshMillis;

    InvariantMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;

        for (Probe probe : PROBES) {
            Gauge.builder(probe.name(), this, self -> self.values.getOrDefault(probe.name(), Double.NaN))
                    .description(probe.description())
                    .register(meterRegistry);
        }

        // 캐시가 늙었는지 스스로 말하게 합니다. 이 값이 없으면 갱신이 멈춘 것과 위반이 없는 것을
        // 구분할 수 없습니다.
        Gauge.builder("paritypay.invariant.refresh_age_seconds", this, InvariantMetrics::refreshAgeSeconds)
                .description("불변조건 지표를 마지막으로 계산한 뒤 지난 시간(초). 첫 계산 전에는 무한대입니다")
                .register(meterRegistry);

        Gauge.builder("paritypay.invariant.refresh_duration_seconds", this, self -> self.lastRefreshMillis / 1000.0d)
                .description("불변조건 지표 한 바퀴를 계산하는 데 걸린 시간(초). 원장이 커지면 함께 자랍니다")
                .register(meterRegistry);
    }

    /**
     * 값을 다시 계산합니다.
     *
     * <p>스크레이프 횟수·수집기 수와 무관하게 이 주기만큼만 DB를 씁니다.
     *
     * <p>이 작업은 원장 크기에 비례해 길어집니다. 스케줄러 스레드가 하나뿐이면 그동안 Outbox 발행이
     * 멈추므로 {@code spring.task.scheduling.pool.size}를 함께 올려 두었습니다.
     */
    @Scheduled(
            fixedDelayString = "${paritypay.observability.invariant-refresh-ms:30000}",
            initialDelayString = "${paritypay.observability.invariant-initial-delay-ms:5000}")
    public void refresh() {
        Instant startedAt = clock.instant();
        try {
            for (Probe probe : PROBES) {
                values.put(probe.name(), count(probe.sql()));
            }
            refreshedAt = clock.instant();
            lastRefreshMillis = Duration.between(startedAt, refreshedAt).toMillis();
        } catch (RuntimeException e) {
            // 마지막 값을 그대로 두고 나이가 자라게 둡니다. 0으로 덮으면 위반이 사라진 것처럼 보입니다.
            log.error("failed to refresh invariant metrics", e);
        }
    }

    private double refreshAgeSeconds() {
        Instant last = refreshedAt;
        if (last == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0d, Duration.between(last, clock.instant()).toMillis() / 1000.0d);
    }

    private double count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0.0d : value.doubleValue();
    }

    private record Probe(String name, String description, String sql) {}
}
