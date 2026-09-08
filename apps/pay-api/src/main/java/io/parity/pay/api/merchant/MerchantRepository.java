package io.parity.pay.api.merchant;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 판매자 조회·등록. */
@Repository
class MerchantRepository {

    private static final RowMapper<Merchant> ROW_MAPPER = (rs, rowNum) -> new Merchant(
            MerchantId.of(rs.getObject("merchant_id", UUID.class)),
            rs.getString("name"),
            MemberId.of(rs.getObject("owner_member_id", UUID.class)),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    MerchantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insert(Merchant merchant, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO merchant (merchant_id, name, owner_member_id, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                merchant.id().value(),
                merchant.name(),
                merchant.ownerMemberId().value(),
                merchant.status(),
                Timestamp.from(now));
    }

    Optional<Merchant> findByOwner(MemberId memberId) {
        List<Merchant> rows = jdbcTemplate.query(
                "SELECT merchant_id, name, owner_member_id, status, created_at FROM merchant"
                        + " WHERE owner_member_id = ?",
                ROW_MAPPER,
                memberId.value());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
