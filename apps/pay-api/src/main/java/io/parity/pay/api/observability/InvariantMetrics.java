package io.parity.pay.api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 금융 불변조건 위반을 상시 감시하는 지표.
 *
 * <p>이 값들은 평소에 전부 0이어야 합니다. 0이 아니면 시스템이 스스로 규칙을 어긴 것이므로 한
 * 건이라도 즉시 경보 대상입니다. 성공률·지연 같은 지표는 임계치를 기준선에서 정해야 하지만, 이
 * 지표들의 임계치는 처음부터 0입니다.
 *
 * <p>근거: docs/05-technical-design.md §12(경보), INV-001·INV-003·INV-010
 */
@Component
class InvariantMetrics {

    private final JdbcTemplate jdbcTemplate;

    InvariantMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;

        gauge(
                meterRegistry,
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
                """);

        gauge(
                meterRegistry,
                "paritypay.invariant.negative_wallet_balances",
                "INV-003 위반: 가용 잔액이 음수인 지갑 수 (항상 0이어야 합니다)",
                "SELECT count(*) FROM wallet_balance WHERE available_amount < 0 OR pending_amount < 0");

        gauge(
                meterRegistry,
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
                """);

        gauge(
                meterRegistry,
                "paritypay.invariant.over_cancelled_payments",
                "INV-005 위반: 누적 취소액이 승인액을 넘은 결제 수 (항상 0이어야 합니다)",
                """
                SELECT count(*) FROM payment
                 WHERE completed_cancellation_amount + processing_cancellation_amount > approved_amount
                """);

        // 운영 상태 지표입니다. 0이 아닌 것이 곧 사고는 아니지만 오래 쌓이면 사고입니다.
        gauge(
                meterRegistry,
                "paritypay.topup.unknown",
                "결과가 확정되지 않은 충전 수",
                "SELECT count(*) FROM top_up WHERE status IN ('UNKNOWN', 'PROCESSING')");

        gauge(
                meterRegistry,
                "paritypay.recovery.manual_review",
                "자동 복구를 포기하고 사람이 확인해야 하는 건수",
                "SELECT count(*) FROM top_up_recovery WHERE requires_manual_review = true");

        gauge(
                meterRegistry,
                "paritypay.settlement.unsettled_payouts",
                "지급 결과가 확정되지 않은 정산 수",
                "SELECT count(*) FROM settlement WHERE status IN ('PAYING', 'UNKNOWN')");

        gauge(
                meterRegistry,
                "paritypay.reconciliation.open_mismatches",
                "해결되지 않은 대사 불일치 수",
                "SELECT count(*) FROM reconciliation_mismatch WHERE resolution_status = 'OPEN'");
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, this, self -> self.count(sql))
                .description(description)
                .register(registry);
    }

    private double count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0.0d : value.doubleValue();
    }
}
