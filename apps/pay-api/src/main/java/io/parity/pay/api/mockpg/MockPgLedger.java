package io.parity.pay.api.mockpg;

import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.money.Money;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock PG가 자기 장부에 남기는 기록.
 *
 * <p>우리 원장이 아닙니다. 외부기관이 무엇을 알고 있는지를 흉내 내는 자리이며, 대사와 복구 조회가
 * 이 기록을 봅니다.
 *
 * <p>쓰기는 {@link Propagation#REQUIRES_NEW}입니다. 호출자의 트랜잭션이 롤백돼도 외부에서 일어난
 * 일은 되돌아가지 않기 때문입니다. 같은 프로세스 대역이라고 해서 외부를 우리 트랜잭션에 묶으면
 * 실험이 현실과 달라집니다. 근거: docs/05-technical-design.md §10
 */
@Component
public class MockPgLedger {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    MockPgLedger(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 승인을 기록합니다.
     *
     * <p>같은 외부 키로 다시 오면 새로 승인하지 않고 기존 기록을 돌려줍니다. 외부기관의 멱등성을
     * 흉내 내는 부분입니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String approve(String externalKey, MerchantId merchantId, String orderId, Money amount) {
        Optional<String> existing = findApprovalId(externalKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID approvalId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_approval
                    (approval_id, external_key, merchant_id, order_id, amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'APPROVED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                approvalId,
                externalKey,
                merchantId.value(),
                orderId,
                amount.amount(),
                Timestamp.from(clock.instant()));
        return findApprovalId(externalKey).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decline(String externalKey, MerchantId merchantId, String orderId, Money amount) {
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_approval
                    (approval_id, external_key, merchant_id, order_id, amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'DECLINED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                UUID.randomUUID(),
                externalKey,
                merchantId.value(),
                orderId,
                amount.amount(),
                Timestamp.from(clock.instant()));
    }

    /** 환불을 기록합니다. 같은 외부 키로 다시 오면 새로 환불하지 않습니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String refund(String externalKey, String paymentKey, Money amount) {
        Optional<String> existing = findRefundId(externalKey);
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
                amount.amount(),
                Timestamp.from(clock.instant()));
        return findRefundId(externalKey).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void declineRefund(String externalKey, String paymentKey, Money amount) {
        jdbcTemplate.update(
                """
                INSERT INTO mock_pg_refund (refund_id, external_key, payment_key, amount, status, created_at)
                VALUES (?, ?, ?, ?, 'DECLINED', ?)
                ON CONFLICT (external_key) DO NOTHING
                """,
                UUID.randomUUID(),
                externalKey,
                paymentKey,
                amount.amount(),
                Timestamp.from(clock.instant()));
    }

    public Optional<String> refundStatusOf(String externalKey) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT status FROM mock_pg_refund WHERE external_key = ?", externalKey);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of((String) rows.get(0).get("status"));
    }

    public Optional<String> findRefundId(String externalKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT refund_id FROM mock_pg_refund WHERE external_key = ? AND status = 'REFUNDED'", externalKey);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(rows.get(0).get("refund_id").toString());
    }

    /** 외부에 남은 기록입니다. 없으면 비어 있습니다. */
    public Optional<String> statusOf(String externalKey) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT status FROM mock_pg_approval WHERE external_key = ?", externalKey);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of((String) rows.get(0).get("status"));
    }

    /** 승인 참조입니다. 우리 결제 행에 저장해 두고 대사에 씁니다. */
    public Optional<String> findApprovalId(String externalKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT approval_id FROM mock_pg_approval WHERE external_key = ? AND status = 'APPROVED'", externalKey);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(rows.get(0).get("approval_id").toString());
    }
}
