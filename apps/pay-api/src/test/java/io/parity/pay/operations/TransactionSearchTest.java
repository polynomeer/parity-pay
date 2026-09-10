package io.parity.pay.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.operations.TransactionSearchService;
import io.parity.pay.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 식별자 해석 (FE-M4).
 *
 * <p>운영자는 고객이 들고 온 값이 무엇인지 모릅니다. 무엇을 넣든 타임라인을 열 수 있는 참조가
 * 나와야 합니다.
 */
@SpringBootTest(classes = ParityPayApplication.class)
class TransactionSearchTest extends AbstractIntegrationTest {

    @Autowired
    private TransactionSearchService searchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OnboardingService onboardingService;

    private UUID memberId;
    private UUID walletId;
    private UUID paymentId;
    private final String orderId = "ord-search-1";

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, outbox_event,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        // 회원·지갑은 실제 서비스로 만듭니다. 시험이 스키마 세부를 직접 알고 있으면 스키마가
        // 바뀔 때마다 의미 없이 깨집니다.
        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("search-" + UUID.randomUUID() + "@example.com", "password1234");
        memberId = registered.memberId().value();
        walletId = registered.walletId();
        paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO payment (payment_id, order_id, member_id, wallet_id, merchant_id, status,
                                     requested_amount, approved_amount, completed_cancellation_amount,
                                     processing_cancellation_amount, currency, method, idempotency_key,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'APPROVED', 30000, 30000, 0, 0, 'KRW', 'PAY_MONEY', ?, now(), now())
                """,
                paymentId,
                orderId,
                memberId,
                walletId,
                UUID.randomUUID(),
                "key-" + paymentId);
    }

    @Test
    @DisplayName("결제 ID는 그대로 타임라인 참조가 된다")
    void resolvesPaymentId() {
        TransactionSearchService.SearchResult result = searchService.resolve(paymentId.toString());

        assertThat(result.kind()).isEqualTo("PAYMENT");
        assertThat(result.references()).singleElement().satisfies(reference -> assertThat(reference.referenceId())
                .isEqualTo(paymentId.toString()));
    }

    @Test
    @DisplayName("주문 ID처럼 UUID가 아닌 값도 찾는다")
    void resolvesOrderId() {
        TransactionSearchService.SearchResult result = searchService.resolve(orderId);

        assertThat(result.kind()).isEqualTo("ORDER");
        assertThat(result.references()).hasSize(1);
    }

    @Test
    @DisplayName("지갑 ID는 거래 하나가 아니라 여러 후보를 돌려준다")
    void resolvesWalletToManyReferences() {
        // 지갑은 거래 여럿을 가리킵니다. 하나의 타임라인으로 합칠 수 없다는 것이 설계의 요점입니다.
        TransactionSearchService.SearchResult result = searchService.resolve(walletId.toString());

        assertThat(result.kind()).isEqualTo("WALLET");
        assertThat(result.references()).anySatisfy(reference -> {
            assertThat(reference.kind()).isEqualTo("PAYMENT");
            assertThat(reference.referenceId()).isEqualTo(paymentId.toString());
        });
    }

    @Test
    @DisplayName("회원 ID는 지갑을 거쳐 거래에 닿는다")
    void resolvesMember() {
        TransactionSearchService.SearchResult result = searchService.resolve(memberId.toString());

        assertThat(result.kind()).isEqualTo("MEMBER");
        assertThat(result.references())
                .anySatisfy(reference -> assertThat(reference.referenceId()).isEqualTo(walletId.toString()));
    }

    @Test
    @DisplayName("이벤트 ID는 그 이벤트가 가리키는 업무 거래로 옮겨진다")
    void resolvesEventToAggregate() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_event (event_id, event_type, event_version, aggregate_type, aggregate_id,
                                          partition_key, payload, status, attempt_count, occurred_at,
                                          created_at, next_attempt_at)
                VALUES (?, 'PaymentApproved', 1, 'PAYMENT', ?, ?, '{}'::jsonb, 'PUBLISHED', 0, ?, ?, ?)
                """,
                eventId,
                paymentId.toString(),
                walletId.toString(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));

        TransactionSearchService.SearchResult result = searchService.resolve(eventId.toString());

        assertThat(result.kind()).isEqualTo("EVENT");
        // 이벤트 ID 자체가 아니라 업무 거래를 돌려줘야 타임라인이 의미를 갖습니다.
        assertThat(result.references()).singleElement().satisfies(reference -> assertThat(reference.referenceId())
                .isEqualTo(paymentId.toString()));
    }

    @Test
    @DisplayName("모르는 값은 못 찾았다고 말한다")
    void unknownIdentifier() {
        TransactionSearchService.SearchResult result =
                searchService.resolve(UUID.randomUUID().toString());

        assertThat(result.kind()).isEqualTo("UNKNOWN");
        assertThat(result.references()).isEmpty();
    }
}
