package io.parity.pay.settlement.adapter.out.persistence;

import io.parity.pay.settlement.application.port.out.SettlementItemRepository;
import io.parity.pay.settlement.application.port.out.SettlementRepository;
import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.settlement.domain.SettlementItem;
import io.parity.pay.settlement.domain.SettlementItem.SettlementItemStatus;
import io.parity.pay.settlement.domain.SettlementItemType;
import io.parity.pay.settlement.domain.SettlementStatus;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 정산 저장소. 집계와 조건부 갱신이 중심이라 JDBC를 직접 사용합니다. */
@Repository
class SettlementPersistenceAdapter implements SettlementRepository, SettlementItemRepository {

    private static final RowMapper<Settlement> SETTLEMENT_MAPPER = (rs, rowNum) -> {
        CurrencyCode currency = CurrencyCode.valueOf(rs.getString("currency"));
        return new Settlement(
                SettlementId.of(rs.getObject("settlement_id", UUID.class)),
                MerchantId.of(rs.getObject("merchant_id", UUID.class)),
                rs.getObject("period_start", java.time.LocalDate.class),
                rs.getObject("period_end", java.time.LocalDate.class),
                Money.of(rs.getLong("gross_amount"), currency),
                Money.of(rs.getLong("cancellation_amount"), currency),
                Money.of(rs.getLong("fee_amount"), currency),
                rs.getLong("adjustment_amount"),
                Money.of(rs.getLong("net_amount"), currency),
                currency,
                SettlementStatus.valueOf(rs.getString("status")),
                rs.getString("external_reference_id"),
                rs.getString("hold_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant());
    };

    private static final RowMapper<SettlementItem> ITEM_MAPPER = (rs, rowNum) -> new SettlementItem(
            rs.getObject("item_id", UUID.class),
            rs.getObject("settlement_id", UUID.class) == null
                    ? null
                    : SettlementId.of(rs.getObject("settlement_id", UUID.class)),
            MerchantId.of(rs.getObject("merchant_id", UUID.class)),
            PaymentId.of(rs.getObject("payment_id", UUID.class)),
            SettlementItemType.valueOf(rs.getString("item_type")),
            rs.getLong("amount"),
            CurrencyCode.valueOf(rs.getString("currency")),
            SettlementItemStatus.valueOf(rs.getString("status")),
            rs.getString("source_reference_id"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    SettlementPersistenceAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<Settlement> findById(SettlementId settlementId) {
        List<Settlement> rows = jdbcTemplate.query(
                "SELECT * FROM settlement WHERE settlement_id = ?",
                SETTLEMENT_MAPPER,
                settlementId.value());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Settlement> findByMerchant(MerchantId merchantId, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM settlement WHERE merchant_id = ? ORDER BY period_start DESC LIMIT ?",
                SETTLEMENT_MAPPER,
                merchantId.value(),
                limit);
    }

    @Override
    public Settlement save(Settlement settlement) {
        int updated = jdbcTemplate.update(
                """
                UPDATE settlement
                   SET status = ?, external_reference_id = ?, hold_reason = ?,
                       paid_at = ?, updated_at = ?, version = version + 1
                 WHERE settlement_id = ?
                """,
                settlement.status().name(),
                settlement.externalReferenceId(),
                settlement.holdReason(),
                settlement.paidAt() == null ? null : Timestamp.from(settlement.paidAt()),
                Timestamp.from(settlement.updatedAt()),
                settlement.id().value());

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO settlement
                        (settlement_id, merchant_id, period_start, period_end, gross_amount,
                         cancellation_amount, fee_amount, adjustment_amount, net_amount, currency,
                         status, external_reference_id, hold_reason, created_at, updated_at, paid_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    settlement.id().value(),
                    settlement.merchantId().value(),
                    settlement.periodStart(),
                    settlement.periodEnd(),
                    settlement.grossAmount().amount(),
                    settlement.cancellationAmount().amount(),
                    settlement.feeAmount().amount(),
                    settlement.adjustmentAmount(),
                    settlement.netAmount().amount(),
                    settlement.currency().name(),
                    settlement.status().name(),
                    settlement.externalReferenceId(),
                    settlement.holdReason(),
                    Timestamp.from(settlement.createdAt()),
                    Timestamp.from(settlement.updatedAt()),
                    settlement.paidAt() == null ? null : Timestamp.from(settlement.paidAt()));
        }
        return settlement;
    }

    @Override
    public boolean append(SettlementItem item) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO settlement_item
                    (item_id, settlement_id, merchant_id, payment_id, item_type, amount, currency,
                     status, source_reference_id, occurred_at, created_at)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (item_type, source_reference_id) DO NOTHING
                """,
                item.itemId(),
                item.merchantId().value(),
                item.paymentId().value(),
                item.type().name(),
                item.amount(),
                item.currency().name(),
                item.status().name(),
                item.sourceReferenceId(),
                Timestamp.from(item.occurredAt()),
                Timestamp.from(clock.instant()));
        return inserted == 1;
    }

    @Override
    public List<SettlementItem> findEligible(MerchantId merchantId, Instant until, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM settlement_item
                 WHERE merchant_id = ? AND status = 'ELIGIBLE' AND occurred_at <= ?
                 ORDER BY occurred_at, item_id
                 LIMIT ?
                """,
                ITEM_MAPPER,
                merchantId.value(),
                Timestamp.from(until),
                limit);
    }

    @Override
    public List<SettlementItem> findByPaymentId(PaymentId paymentId) {
        return jdbcTemplate.query(
                "SELECT * FROM settlement_item WHERE payment_id = ? ORDER BY occurred_at",
                ITEM_MAPPER,
                paymentId.value());
    }

    @Override
    public List<SettlementItem> findBySettlementId(SettlementId settlementId) {
        return jdbcTemplate.query(
                "SELECT * FROM settlement_item WHERE settlement_id = ? ORDER BY occurred_at",
                ITEM_MAPPER,
                settlementId.value());
    }

    @Override
    public void assignToSettlement(List<SettlementItem> items, SettlementId settlementId) {
        for (SettlementItem item : items) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE settlement_item
                       SET settlement_id = ?, status = 'SETTLED'
                     WHERE item_id = ? AND status = 'ELIGIBLE'
                    """,
                    settlementId.value(),
                    item.itemId());
            if (updated != 1) {
                // 다른 회차가 이미 가져간 항목입니다. 같은 금액이 두 정산에 들어가면 안 됩니다.
                throw new IllegalStateException(
                        "settlement item " + item.itemId() + " was already assigned");
            }
        }
    }
}
