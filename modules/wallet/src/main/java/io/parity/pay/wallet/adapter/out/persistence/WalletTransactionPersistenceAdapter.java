package io.parity.pay.wallet.adapter.out.persistence;

import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.port.out.WalletTransactionRepository;
import io.parity.pay.wallet.domain.WalletTransactionEntry;
import io.parity.pay.wallet.domain.WalletTransactionEntry.TransactionDirection;
import io.parity.pay.wallet.domain.WalletTransactionEntry.WalletTransactionType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 거래내역 프로젝션 저장소. 단순 append와 커서 조회만 필요하므로 JDBC를 직접 사용합니다. */
@Repository
class WalletTransactionPersistenceAdapter implements WalletTransactionRepository {

    private static final RowMapper<WalletTransactionEntry> ROW_MAPPER = (rs, rowNum) ->
            new WalletTransactionEntry(
                    rs.getObject("transaction_id", UUID.class),
                    WalletId.of(rs.getObject("wallet_id", UUID.class)),
                    WalletTransactionType.valueOf(rs.getString("transaction_type")),
                    TransactionDirection.valueOf(rs.getString("direction")),
                    Money.of(rs.getLong("amount"), CurrencyCode.valueOf(rs.getString("currency"))),
                    rs.getString("reference_type"),
                    rs.getString("reference_id"),
                    rs.getObject("ledger_transaction_id", UUID.class) == null
                            ? null
                            : LedgerTransactionId.of(rs.getObject("ledger_transaction_id", UUID.class)),
                    rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    WalletTransactionPersistenceAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public boolean append(WalletTransactionEntry entry) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO wallet_transaction
                    (transaction_id, wallet_id, transaction_type, direction, amount, currency,
                     reference_type, reference_id, ledger_transaction_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (reference_type, reference_id) DO NOTHING
                """,
                entry.transactionId(),
                entry.walletId().value(),
                entry.type().name(),
                entry.direction().name(),
                entry.amount().amount(),
                entry.amount().currency().name(),
                entry.referenceType(),
                entry.referenceId(),
                entry.ledgerTransactionId() == null ? null : entry.ledgerTransactionId().value(),
                Timestamp.from(entry.occurredAt()),
                Timestamp.from(clock.instant()));
        return inserted == 1;
    }

    @Override
    public List<WalletTransactionEntry> findPage(
            WalletId walletId, Instant beforeOccurredAt, UUID beforeTransactionId, int limit) {
        if (beforeOccurredAt == null) {
            return jdbcTemplate.query(
                    """
                    SELECT * FROM wallet_transaction
                     WHERE wallet_id = ?
                     ORDER BY occurred_at DESC, transaction_id DESC
                     LIMIT ?
                    """,
                    ROW_MAPPER,
                    walletId.value(),
                    limit);
        }
        // (occurred_at, transaction_id) 튜플 비교로 같은 시각의 거래도 안정적으로 이어집니다.
        return jdbcTemplate.query(
                """
                SELECT * FROM wallet_transaction
                 WHERE wallet_id = ?
                   AND (occurred_at, transaction_id) < (?, ?)
                 ORDER BY occurred_at DESC, transaction_id DESC
                 LIMIT ?
                """,
                ROW_MAPPER,
                walletId.value(),
                Timestamp.from(beforeOccurredAt),
                beforeTransactionId,
                limit);
    }
}
