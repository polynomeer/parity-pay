package io.parity.pay.api.idempotency;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.idempotency.IdempotencyRecord;
import io.parity.pay.shared.idempotency.IdempotencyStatus;
import io.parity.pay.shared.idempotency.IdempotencyStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등 요청 저장소.
 *
 * <p>키 선점은 {@code INSERT ... ON CONFLICT DO NOTHING} 한 문장으로 합니다.
 *
 * <ul>
 *   <li>"조회 후 없으면 삽입"은 동시 요청 두 건이 모두 최초라고 판단하는 경쟁 조건이 있습니다.
 *   <li>제약 위반을 예외로 잡아 복구하는 방식은 PostgreSQL에서 트랜잭션 전체가 중단되므로 같은
 *       트랜잭션 안에서 기존 기록을 다시 읽을 수 없습니다.
 * </ul>
 *
 * <p>충돌 시 INSERT는 선행 트랜잭션의 커밋을 기다린 뒤 아무것도 하지 않고, 이어지는 SELECT가 커밋된
 * 기록을 읽습니다. 근거: docs/09-consistency-recovery.md §3, INV-004
 */
@Component
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    JdbcIdempotencyStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyRecord beginOrGet(
            UUID principalId, String operation, IdempotencyKey key, String requestHash) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_record
                    (principal_id, operation, idempotency_key, request_hash, status,
                     business_reference_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
                ON CONFLICT (principal_id, operation, idempotency_key) DO NOTHING
                """,
                principalId,
                operation,
                key.value(),
                requestHash,
                IdempotencyStatus.PROCESSING.name(),
                now,
                now);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT request_hash, status, business_reference_id
                  FROM idempotency_record
                 WHERE principal_id = ? AND operation = ? AND idempotency_key = ?
                """,
                principalId,
                operation,
                key.value());
        if (rows.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "idempotency record disappeared for " + operation);
        }

        Map<String, Object> row = rows.get(0);
        return new IdempotencyRecord(
                principalId,
                operation,
                key,
                (String) row.get("request_hash"),
                IdempotencyStatus.valueOf((String) row.get("status")),
                (UUID) row.get("business_reference_id"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(
            UUID principalId,
            String operation,
            IdempotencyKey key,
            IdempotencyStatus status,
            UUID businessReferenceId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE idempotency_record
                   SET status = ?, business_reference_id = ?, updated_at = ?
                 WHERE principal_id = ? AND operation = ? AND idempotency_key = ?
                """,
                status.name(),
                businessReferenceId,
                Timestamp.from(clock.instant()),
                principalId,
                operation,
                key.value());
        if (updated != 1) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "idempotency record not found for operation " + operation);
        }
    }
}
