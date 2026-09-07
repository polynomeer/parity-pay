package io.parity.pay.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.event.EventEnvelope;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이벤트 계약 검사가 실제로 작동하는지 봅니다.
 *
 * <p>검사기가 켜져 있어도 통과만 시키면 게이트가 아닙니다. 여기서는 계약을 어긴 이벤트가 기록
 * 단계에서 막히는지, 그리고 막힐 때 업무 트랜잭션까지 되돌아가는지 확인합니다.
 *
 * <p>정상 이벤트가 계약을 만족한다는 것은 이 테스트가 아니라 나머지 통합 테스트 전체가 증명합니다.
 * 테스트 프로필에서 검사가 켜져 있으므로, 어떤 흐름이 계약을 어기는 이벤트를 만들면 그 흐름의
 * 테스트가 실패합니다.
 *
 * <p>근거: docs/08-db-api-event-spec.md §7·§8
 */
class EventSchemaContractTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxAppender outboxAppender;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE outbox_event");
    }

    @Test
    @DisplayName("계약을 만족하는 이벤트는 기록된다")
    void validEventIsAppended() {
        transactionTemplate.executeWithoutResult(status -> outboxAppender.append(walletCreated(payload -> {})));

        assertThat(outboxCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("스키마에 없는 필드가 들어가면 기록 단계에서 막힌다")
    void undeclaredPayloadFieldIsRejected() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> outboxAppender.append(
                        // 계좌번호처럼 넣으면 안 되는 값이 payload에 섞여 들어오는 상황입니다.
                        walletCreated(payload -> payload.put("accountNumber", "110123456789")))))
                .hasMessageContaining("WalletCreated-v1")
                .hasMessageContaining("accountNumber");

        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("필수 필드가 빠지면 기록 단계에서 막힌다")
    void missingRequiredFieldIsRejected() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                        status -> outboxAppender.append(walletCreated(payload -> payload.remove("currency")))))
                .hasMessageContaining("currency");

        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("타입이 다르면 기록 단계에서 막힌다")
    void wrongTypeIsRejected() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                        outboxAppender.append(walletCreated(payload -> payload.put("walletId", "not-a-uuid")))))
                .hasMessageContaining("walletId");

        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("스키마가 없는 이벤트 타입은 기록되지 않는다")
    void unknownEventTypeIsRejected() {
        EventEnvelope unknown = EventEnvelope.of(
                "SomethingNobodyDeclared",
                1,
                "Wallet",
                WalletId.generate().toString(),
                Instant.parse("2026-09-07T00:00:00Z"),
                null,
                Map.of("walletId", WalletId.generate().toString()));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> outboxAppender.append(unknown)))
                .hasMessageContaining("no schema for event SomethingNobodyDeclared-v1");

        assertThat(outboxCount()).isZero();
    }

    /** 정상 payload를 만든 뒤 테스트가 원하는 대로 망가뜨립니다. */
    private static EventEnvelope walletCreated(java.util.function.Consumer<Map<String, Object>> mutation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletId", WalletId.generate().toString());
        payload.put("memberId", MemberId.generate().toString());
        payload.put("currency", "KRW");
        mutation.accept(payload);
        return EventEnvelope.of(
                "WalletCreated",
                1,
                "Wallet",
                WalletId.generate().toString(),
                Instant.parse("2026-09-07T00:00:00Z"),
                null,
                payload);
    }

    private long outboxCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Long.class);
        return count == null ? 0L : count;
    }
}
