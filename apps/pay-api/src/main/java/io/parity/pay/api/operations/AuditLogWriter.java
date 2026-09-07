package io.parity.pay.api.operations;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 운영자 작업 감사 로그.
 *
 * <p>주체·사유·전후 상태·결과를 남깁니다. 결과가 "변화 없음"이어도 기록합니다. 무엇을 시도했는지가
 * 사고 조사에서 중요하기 때문입니다. 테이블은 트리거로 append-only입니다. 근거: NFR-006
 */
@Component
public class AuditLogWriter {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    AuditLogWriter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void record(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String reason,
            String beforeState,
            String afterState,
            Result result,
            String detail) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_log
                    (audit_id, actor, action, resource_type, resource_id, reason,
                     before_state, after_state, result, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                actor,
                action,
                resourceType,
                resourceId,
                reason,
                beforeState,
                afterState,
                result.name(),
                detail,
                Timestamp.from(clock.instant()));
    }

    public enum Result {
        SUCCEEDED,
        FAILED,
        NO_CHANGE
    }
}
