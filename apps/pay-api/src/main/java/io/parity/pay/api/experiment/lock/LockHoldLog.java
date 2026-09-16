package io.parity.pay.api.experiment.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 락 보유 구간의 증거. 실험 전용 표 {@code experiment_lock_hold}에 남깁니다.
 *
 * <p>업무 트랜잭션과 **다른 커넥션 풀**(autocommit)로 씁니다. 업무 트랜잭션이 롤백되어도(fencing 거부,
 * 잔액 부족) 그 시도가 락을 언제부터 언제까지 쥐고 있었는지는 남아야 합니다. 소유 겹침은 로그가 아니라
 * 이 표에서 SQL로 셉니다 — 같은 지갑의 두 행이 {@code [acquired_at, released_at]} 구간에서 겹치면
 * 두 소유자가 동시에 있었던 것입니다.
 *
 * <p>fencing(c-2)의 "읽는 순간 토큰 새기기"도 여기서 합니다. 업무 트랜잭션 안에서 하면 fence 행의 잠금이
 * 커밋까지 이어져 그것이 곧 비관적 잠금이 되어 버립니다 — 그러면 fencing이 아니라 DB 행 잠금이 정합성을
 * 지킨 것이 됩니다. 별도 짧은 트랜잭션이어야 fencing 자체를 잽니다.
 */
class LockHoldLog implements AutoCloseable {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    LockHoldLog(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    void createTables() {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS experiment_lock_hold (
                    hold_id        BIGSERIAL PRIMARY KEY,
                    run_id         TEXT        NOT NULL,
                    mode           TEXT        NOT NULL,
                    wallet_id      UUID        NOT NULL,
                    token          BIGINT,
                    acquired_at    TIMESTAMPTZ NOT NULL,
                    read_amount    BIGINT,
                    write_amount   BIGINT,
                    wrote_at       TIMESTAMPTZ,
                    released_at    TIMESTAMPTZ,
                    renewals       INT         NOT NULL DEFAULT 0,
                    release_result TEXT,
                    outcome        TEXT
                )
                """);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS ix_experiment_lock_hold_wallet ON experiment_lock_hold (wallet_id, acquired_at)");
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS experiment_wallet_fence (
                    wallet_id   UUID   PRIMARY KEY,
                    fence_token BIGINT NOT NULL
                )
                """);
    }

    long acquired(String runId, String mode, UUID walletId, Long token) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO experiment_lock_hold (run_id, mode, wallet_id, token, acquired_at)
                VALUES (?, ?, ?, ?, ?) RETURNING hold_id
                """,
                Long.class,
                runId,
                mode,
                walletId,
                token,
                now());
        return id == null ? -1L : id;
    }

    void wrote(long holdId, Long readAmount, Long writeAmount, String outcome) {
        jdbc.update(
                "UPDATE experiment_lock_hold SET read_amount = ?, write_amount = ?, wrote_at = ?, outcome = ? WHERE hold_id = ?",
                readAmount,
                writeAmount,
                now(),
                outcome,
                holdId);
    }

    void outcome(long holdId, Long readAmount, String outcome) {
        jdbc.update(
                "UPDATE experiment_lock_hold SET read_amount = ?, outcome = ? WHERE hold_id = ?",
                readAmount,
                outcome,
                holdId);
    }

    void released(long holdId, String releaseResult, int renewals, boolean committed) {
        jdbc.update(
                """
                UPDATE experiment_lock_hold
                   SET released_at = ?, release_result = ?, renewals = ?,
                       outcome = CASE WHEN ? THEN outcome ELSE coalesce(outcome, 'ROLLED_BACK') END
                 WHERE hold_id = ?
                """,
                now(),
                releaseResult,
                renewals,
                committed,
                holdId);
    }

    /**
     * fencing(c-2): 토큰을 새기면서 잔액을 읽습니다. 하나의 짧은 트랜잭션입니다.
     *
     * <p>순서가 중요합니다. 먼저 fence를 올리고(내 토큰이 더 클 때만), 그 다음 잔액을 {@code FOR SHARE}로
     * 읽습니다. FOR SHARE는 아직 커밋되지 않은 다른 소유자의 잔액 쓰기가 있으면 그 커밋까지 기다렸다가
     * 읽습니다 — 그래서 "옛 소유자가 쓰고 아직 커밋하지 않은 사이에 새 소유자가 낡은 값을 읽는" 창이
     * 닫힙니다. 이것이 없으면 fencing은 옛 소유자의 늦은 쓰기만 막고, 새 소유자의 낡은 읽기는 못 막습니다.
     *
     * @return 잔액. 더 큰 토큰이 이미 새겨져 있으면(내가 낡은 소유자) 비어 있습니다
     */
    OptionalLong claimAndRead(UUID walletId, long token) {
        // 업무 트랜잭션(JPA)과 섞이지 않도록 Spring 트랜잭션 관리자 없이 커넥션을 직접 씁니다.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int claimed;
                try (PreparedStatement claim = connection.prepareStatement(
                        """
                        INSERT INTO experiment_wallet_fence (wallet_id, fence_token) VALUES (?, ?)
                        ON CONFLICT (wallet_id) DO UPDATE SET fence_token = EXCLUDED.fence_token
                        WHERE experiment_wallet_fence.fence_token < EXCLUDED.fence_token
                        """)) {
                    claim.setObject(1, walletId);
                    claim.setLong(2, token);
                    claimed = claim.executeUpdate();
                }
                if (claimed == 0) {
                    connection.rollback();
                    return OptionalLong.empty();
                }
                Long amount = null;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT available_amount FROM wallet_balance WHERE wallet_id = ? FOR SHARE")) {
                    read.setObject(1, walletId);
                    try (ResultSet rs = read.executeQuery()) {
                        if (rs.next()) {
                            amount = rs.getLong(1);
                        }
                    }
                }
                connection.commit();
                return amount == null ? OptionalLong.empty() : OptionalLong.of(amount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fence claim failed", e);
        }
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("failed to close the experiment pool", e);
            }
        }
    }
}
