package io.parity.pay.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 스케줄 실행 경로 검증.
 *
 * <p>다른 테스트는 {@code publishBatch()}를 주입된 빈으로 호출합니다. 그 경로는 프록시를 거치므로
 * 트랜잭션이 열리고 잘 동작했습니다. 운영에서 실제로 도는 경로는 스케줄 메서드이고, 그 안에서
 * 같은 빈의 메서드를 호출하면 프록시를 거치지 않습니다. 그래서 부하 시험에서 이벤트가 한 건도
 * 발행되지 않았고, 테스트는 전부 통과하고 있었습니다.
 *
 * <p>여기서는 스케줄 메서드를 직접 호출해 그 경로를 고정합니다.
 *
 * <p>근거: ADR-005, reports/11 P-003
 */
@SpringBootTest(properties = "paritypay.events.publisher-enabled=true")
// 이 컨텍스트는 발행기를 켠 채 스케줄러를 돌립니다. 캐시된 채로 남으면 이후 다른 테스트가
// "아직 발행되지 않은 이벤트"를 관찰하려는 순간에 배경에서 발행해 버립니다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxSchedulerTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         outbox_event, consumed_event, wallet_transaction,
                         ledger_entry, ledger_transaction, ledger_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
    }

    @Test
    @DisplayName("스케줄 실행이 실제로 이벤트를 발행한다")
    void scheduledRoundPublishesEvents() {
        onboardingService.registerMember("scheduler@example.com", "password1234");
        assertThat(pendingCount()).isEqualTo(1L);

        // 운영에서 도는 것과 같은 경로입니다.
        outboxPublisher.publishScheduled();

        assertThat(pendingCount()).isZero();
        assertThat(publishedCount()).isEqualTo(1L);
    }

    private long pendingCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'", Long.class);
        return count == null ? 0L : count;
    }

    private long publishedCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'", Long.class);
        return count == null ? 0L : count;
    }
}
