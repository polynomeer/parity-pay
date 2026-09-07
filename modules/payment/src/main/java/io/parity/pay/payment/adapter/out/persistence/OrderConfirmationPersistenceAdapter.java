package io.parity.pay.payment.adapter.out.persistence;

import io.parity.pay.payment.application.port.out.OrderConfirmationRepository;
import io.parity.pay.shared.id.PaymentId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 구매확정 저장소. 삽입과 조회만 필요하므로 JDBC를 직접 사용합니다. */
@Repository
class OrderConfirmationPersistenceAdapter implements OrderConfirmationRepository {

    private final JdbcTemplate jdbcTemplate;

    OrderConfirmationPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean confirm(PaymentId paymentId, String orderId, Instant confirmedAt) {
        // 동시에 두 번 확정 요청이 와도 이벤트는 한 번만 발행됩니다.
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO order_confirmation (payment_id, order_id, confirmed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (payment_id) DO NOTHING
                """,
                paymentId.value(),
                orderId,
                Timestamp.from(confirmedAt));
        return inserted == 1;
    }

    @Override
    public Optional<Instant> findConfirmedAt(PaymentId paymentId) {
        List<Timestamp> rows = jdbcTemplate.queryForList(
                "SELECT confirmed_at FROM order_confirmation WHERE payment_id = ?",
                Timestamp.class,
                paymentId.value());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).toInstant());
    }
}
