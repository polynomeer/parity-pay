package io.parity.pay.api.security;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 실패 추적.
 *
 * <p>실패 기록은 반드시 별도 트랜잭션에서 커밋해야 합니다. 로그인 실패는 예외로 끝나고, 그 예외가
 * 호출자의 트랜잭션을 롤백시키기 때문입니다. 같은 트랜잭션에 기록하면 실패 횟수가 늘 0으로
 * 남아 잠금이 영원히 걸리지 않습니다.
 *
 * <p>근거: docs/05-technical-design.md §11(로그인 실패 제한)
 */
@Component
class LoginAttemptTracker {

    private final JdbcTemplate jdbcTemplate;
    private final SecurityProperties properties;
    private final Clock clock;

    LoginAttemptTracker(JdbcTemplate jdbcTemplate, SecurityProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    void requireNotLocked(String email) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT locked_until FROM login_attempt WHERE email = ?", email);
        if (rows.isEmpty() || rows.get(0).get("locked_until") == null) {
            return;
        }
        Instant lockedUntil = ((Timestamp) rows.get(0).get("locked_until")).toInstant();
        if (lockedUntil.isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.LIMIT_EXCEEDED, "too many failed sign-in attempts; try again later");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(String email) {
        Instant now = clock.instant();
        jdbcTemplate.update(
                """
                INSERT INTO login_attempt (email, failed_count, last_failed_at, updated_at)
                VALUES (?, 1, ?, ?)
                ON CONFLICT (email) DO UPDATE
                   SET failed_count = login_attempt.failed_count + 1,
                       last_failed_at = excluded.last_failed_at,
                       updated_at = excluded.updated_at,
                       locked_until = CASE
                           WHEN login_attempt.failed_count + 1 >= ? THEN ?
                           ELSE login_attempt.locked_until
                       END
                """,
                email,
                Timestamp.from(now),
                Timestamp.from(now),
                properties.maxLoginFailures(),
                Timestamp.from(now.plus(properties.loginLockDuration())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void clearFailures(String email) {
        jdbcTemplate.update("DELETE FROM login_attempt WHERE email = ?", email);
    }
}
