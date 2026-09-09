package io.parity.pay.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 발행 실패 처리.
 *
 * <p>브로커가 응답하지 않을 때 이벤트를 잃지 않고 재시도 대상으로 남기는지, 최대 시도를 넘기면
 * 운영자가 볼 수 있는 상태로 전환하는지 확인합니다. 근거: docs/09-consistency-recovery.md §5·§11
 */
@SpringBootTest(properties = "paritypay.events.max-attempts=2")
class OutboxRetryTest extends AbstractIntegrationTest {

    @MockitoBean
    private MessageBroker messageBroker;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        given(messageBroker.sendAll(anyString(), anyList())).willAnswer(invocation -> {
            List<MessageBroker.OutboxMessage> messages = invocation.getArgument(1);
            List<MessageBroker.SendOutcome> outcomes = new ArrayList<>();
            for (MessageBroker.OutboxMessage message : messages) {
                outcomes.add(MessageBroker.SendOutcome.failed(message.eventId(), "broker is unavailable"));
            }
            return outcomes;
        });
    }

    @Test
    @DisplayName("발행에 실패해도 이벤트는 사라지지 않고 재시도 대상으로 남는다")
    void failedPublishStaysPendingWithBackoff() {
        onboardingService.registerMember("retry@example.com", "password1234");

        int published = outboxPublisher.publishBatch();

        assertThat(published).isZero();
        Map<String, Object> row = outboxRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((Integer) row.get("attempt_count")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("broker is unavailable");
        // 백오프가 적용되어 즉시 다시 시도하지 않습니다.
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant())
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("백오프가 지나기 전에는 같은 이벤트를 다시 집어가지 않는다")
    void backoffDelaysTheNextAttempt() {
        onboardingService.registerMember("retry2@example.com", "password1234");
        outboxPublisher.publishBatch();

        outboxPublisher.publishBatch();

        assertThat((Integer) outboxRow().get("attempt_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("최대 시도를 넘기면 FAILED로 전환해 운영자 확인 대상이 된다")
    void exhaustedRetriesBecomeFailed() {
        onboardingService.registerMember("retry3@example.com", "password1234");

        outboxPublisher.publishBatch();
        // 백오프 시간이 지난 상황을 만듭니다.
        jdbcTemplate.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 minute'");
        outboxPublisher.publishBatch();

        Map<String, Object> row = outboxRow();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((Integer) row.get("attempt_count")).isEqualTo(2);

        // 적체 지표가 실패 건수를 드러냅니다.
        assertThat(meterRegistry.get("paritypay.outbox.failed").gauge().value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("paritypay.outbox.pending").gauge().value())
                .isZero();
    }

    @Test
    @DisplayName("미발행 적체는 건수와 가장 오래된 이벤트의 나이로 드러난다")
    void pendingBacklogIsObservable() {
        onboardingService.registerMember("retry4@example.com", "password1234");

        assertThat(meterRegistry.get("paritypay.outbox.pending").gauge().value())
                .isEqualTo(1.0d);
        assertThat(meterRegistry
                        .get("paritypay.outbox.oldest_pending_age_seconds")
                        .gauge()
                        .value())
                .isGreaterThanOrEqualTo(0.0d);
    }

    @Test
    @DisplayName("배치 일부만 실패하면 확인된 이벤트만 발행되고 실패한 것만 재시도로 남는다")
    void partialBatchFailureOnlyRetriesTheFailedEvent() {
        onboardingService.registerMember("partial1@example.com", "password1234");
        onboardingService.registerMember("partial2@example.com", "password1234");
        // 첫 건만 브로커가 받았다고 답합니다.
        given(messageBroker.sendAll(anyString(), anyList())).willAnswer(invocation -> {
            List<MessageBroker.OutboxMessage> messages = invocation.getArgument(1);
            List<MessageBroker.SendOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                outcomes.add(
                        i == 0
                                ? MessageBroker.SendOutcome.acknowledged(
                                        messages.get(i).eventId())
                                : MessageBroker.SendOutcome.failed(
                                        messages.get(i).eventId(), "not acked"));
            }
            return outcomes;
        });

        int published = outboxPublisher.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'", Long.class))
                .isEqualTo(1L);
        Map<String, Object> pending = jdbcTemplate.queryForMap(
                """
                SELECT status, attempt_count, last_error
                  FROM outbox_event
                 WHERE status = 'PENDING'
                """);
        assertThat(pending.get("last_error")).isEqualTo("not acked");
        assertThat((Integer) pending.get("attempt_count")).isEqualTo(1);
    }

    private Map<String, Object> outboxRow() {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempt_count, next_attempt_at, last_error FROM outbox_event LIMIT 1");
    }
}
