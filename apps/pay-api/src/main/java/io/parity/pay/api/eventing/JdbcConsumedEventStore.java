package io.parity.pay.api.eventing;

import io.parity.pay.shared.event.ConsumedEventStore;
import io.parity.pay.shared.id.EventId;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소비 이력 저장소.
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING}의 반환 행 수로 최초 소비 여부를 판단합니다. 조회 후
 * 삽입은 같은 이벤트를 동시에 소비하는 두 인스턴스를 막지 못합니다.
 *
 * <p>소비 기록과 업무 결과가 같은 트랜잭션에 있어야 하므로 {@link Propagation#MANDATORY}입니다.
 * 근거: docs/09-consistency-recovery.md §6
 */
@Component
class JdbcConsumedEventStore implements ConsumedEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    JdbcConsumedEventStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markConsumed(String consumerName, EventId eventId) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO consumed_event (consumer_name, event_id, consumed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """,
                consumerName,
                eventId.value(),
                Timestamp.from(clock.instant()));
        return inserted == 1;
    }
}
