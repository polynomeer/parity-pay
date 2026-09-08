package io.parity.mockpg;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이 PG의 장부.
 *
 * <p>외부기관도 자체 멱등성을 가진다는 전제를 재현합니다. 같은 {@code externalKey}로 재요청해도
 * 승인·환불은 한 번만 일어납니다. 이 전제가 없으면 우리 복구는 재요청만으로 이중 청구를 만듭니다.
 */
@Component
class PgLedger {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    PgLedger(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    String approve(String externalKey, UUID merchantId, String orderId, long amount) {
        Optional<String> existing = approvalId(externalKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_approval
                    (approval_id, external_key, merchant_id, order_id, amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'APPROVED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                UUID.randomUUID(),
                externalKey,
                merchantId,
                orderId,
                amount,
                Timestamp.from(clock.instant()));
        return approvalId(externalKey).orElseThrow();
    }

    @Transactional
    void decline(String externalKey, UUID merchantId, String orderId, long amount) {
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_approval
                    (approval_id, external_key, merchant_id, order_id, amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'DECLINED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                UUID.randomUUID(),
                externalKey,
                merchantId,
                orderId,
                amount,
                Timestamp.from(clock.instant()));
    }

    @Transactional
    String refund(String externalKey, String paymentKey, long amount) {
        Optional<String> existing = refundId(externalKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_refund (refund_id, external_key, payment_key, amount, status, created_at)
                VALUES (?, ?, ?, ?, 'REFUNDED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                UUID.randomUUID(),
                externalKey,
                paymentKey,
                amount,
                Timestamp.from(clock.instant()));
        return refundId(externalKey).orElseThrow();
    }

    @Transactional
    void declineRefund(String externalKey, String paymentKey, long amount) {
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_refund (refund_id, external_key, payment_key, amount, status, created_at)
                VALUES (?, ?, ?, ?, 'DECLINED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                UUID.randomUUID(),
                externalKey,
                paymentKey,
                amount,
                Timestamp.from(clock.instant()));
    }

    Optional<String> approvalStatus(String externalKey) {
        return firstValue("SELECT status FROM mock_pg_approval WHERE external_key = ?", externalKey);
    }

    Optional<String> refundStatus(String externalKey) {
        return firstValue("SELECT status FROM mock_pg_refund WHERE external_key = ?", externalKey);
    }

    private Optional<String> approvalId(String externalKey) {
        return firstValue(
                "SELECT approval_id::text FROM mock_pg_approval WHERE external_key = ? AND status = 'APPROVED'",
                externalKey);
    }

    private Optional<String> refundId(String externalKey) {
        return firstValue(
                "SELECT refund_id::text FROM mock_pg_refund WHERE external_key = ? AND status = 'REFUNDED'",
                externalKey);
    }

    private Optional<String> firstValue(String sql, String externalKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, externalKey);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(rows.get(0).values().iterator().next()).map(Object::toString);
    }
}
