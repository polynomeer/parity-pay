package io.parity.pay.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.outbox.OutboxPublisher;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.adapter.in.messaging.WalletTransactionProjectionConsumer;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.WalletTransactionQuery;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outbox 발행과 멱등 소비.
 *
 * <p>T-003(업무 롤백 시 이벤트 없음), F-003(커밋 후 발행 전 종료), F-004·T-008(중복 전달),
 * DoD-05·DoD-06, FR-008(거래내역 조회)을 다룹니다.
 */
class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private CancelPaymentUseCase cancelPayment;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private WalletTransactionProjectionConsumer projectionConsumer;

    @Autowired
    private WalletTransactionQuery walletTransactionQuery;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MemberId memberId;
    private WalletId walletId;
    private BankAccountId bankAccountId;
    private MerchantId merchantId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);

        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("events@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        merchantId = MerchantId.generate();
        bankAccountId = onboardingService.linkBankAccount(
                memberId, "004", "110-9999-8888", Money.krw(1_000_000));
    }

    @Test
    @DisplayName("ADR-005: 업무 커밋과 함께 이벤트가 PENDING으로 남는다")
    void businessCommitLeavesPendingEvent() {
        topUp("outbox-topup-00001", 100_000);

        // 가입(WalletCreated) + 충전(TopUpCompleted)
        assertThat(outboxCount("PENDING")).isEqualTo(2L);
        assertThat(outboxEventTypes()).contains("WalletCreated", "TopUpCompleted");
        // 아직 발행 전이므로 프로젝션은 비어 있습니다.
        assertThat(projectionCount()).isZero();
    }

    @Test
    @DisplayName("T-003: 업무가 롤백되면 이벤트도 함께 사라진다")
    void rolledBackBusinessLeavesNoEvent() {
        topUp("outbox-topup-00002", 10_000);
        long before = outboxCount("PENDING");

        // 잔액을 넘는 결제는 확정 실패하고 트랜잭션 전체가 롤백됩니다.
        assertThatThrownBy(() -> pay("order-rollback", "outbox-payment-00001", 999_999))
                .isInstanceOf(BusinessException.class);

        assertThat(outboxCount("PENDING")).isEqualTo(before);
        assertThat(outboxEventTypes()).doesNotContain("PaymentApproved");
    }

    @Test
    @DisplayName("F-003/DoD-05: 커밋 후 발행 전에 멈춰도 재시작한 발행기가 이어서 발행한다")
    void publisherResumesAfterRestart() {
        topUp("outbox-topup-00003", 100_000);
        assertThat(outboxCount("PENDING")).isEqualTo(2L);

        // 발행기가 없는 동안에도 이벤트는 DB에 남아 있습니다. 이제 발행기를 돌립니다.
        int published = outboxPublisher.publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(outboxCount("PENDING")).isZero();
        assertThat(outboxCount("PUBLISHED")).isEqualTo(2L);

        // 브로커로 나간 이벤트를 소비자가 받아 거래내역을 만듭니다.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(projectionCount()).isEqualTo(1L));
    }

    @Test
    @DisplayName("F-004/T-008: 같은 이벤트가 다시 전달되어도 거래내역은 한 줄이다")
    void duplicateDeliveryProducesSingleRow() throws Exception {
        topUp("outbox-topup-00004", 100_000);
        outboxPublisher.publishBatch();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(projectionCount()).isEqualTo(1L));

        // 브로커 ACK 유실로 같은 봉투가 다시 도착한 상황을 그대로 재현합니다.
        JsonNode envelope = objectMapper.readTree(publishedEnvelopeJson("TopUpCompleted"));
        projectionConsumer.consume(envelope);
        projectionConsumer.consume(envelope);

        assertThat(projectionCount()).isEqualTo(1L);
        // 같은 eventId가 세 번 도착했지만 이 소비자의 소비 기록은 한 줄입니다.
        // 소비 이력은 (소비자, 이벤트) 쌍이므로 다른 소비자의 기록과 섞이지 않습니다.
        assertThat(consumedEventCountFor(
                        WalletTransactionProjectionConsumer.CONSUMER_NAME,
                        envelope.get("eventId").asText()))
                .isEqualTo(1L);
        // 이 프로젝션이 무시하는 WalletCreated도 소비 이력에는 남겨 재처리 대상에서 빠지게 합니다.
        assertThat(consumedEventCountFor(WalletTransactionProjectionConsumer.CONSUMER_NAME))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("DoD-06: 소비 이력이 지워져도 업무 유니크 키가 중복을 막는다")
    void businessUniqueKeyIsTheSecondDefence() throws Exception {
        topUp("outbox-topup-00005", 100_000);
        outboxPublisher.publishBatch();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(projectionCount()).isEqualTo(1L));

        // 이 소비자의 이력만 강제로 지워 "처음 보는 이벤트"처럼 만듭니다.
        jdbcTemplate.update(
                "DELETE FROM consumed_event WHERE consumer_name = ?",
                WalletTransactionProjectionConsumer.CONSUMER_NAME);

        JsonNode envelope = objectMapper.readTree(publishedEnvelopeJson("TopUpCompleted"));
        projectionConsumer.consume(envelope);

        assertThat(projectionCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("FR-008: 충전·결제·취소가 거래내역에 방향과 함께 남는다")
    void transactionHistoryReflectsAllEvents() {
        topUp("outbox-topup-00006", 100_000);
        PaymentView payment = pay("order-history", "outbox-payment-00002", 30_000);
        cancelPayment.cancel(new CancelPaymentCommand(
                memberId,
                payment.paymentId(),
                Money.krw(10_000),
                "PARTIAL_RETURN",
                IdempotencyKey.of("outbox-cancel-00001")));

        outboxPublisher.publishBatch();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(projectionCount()).isEqualTo(3L));

        WalletTransactionQuery.TransactionPage page =
                walletTransactionQuery.list(memberId, walletId, null, 20);

        assertThat(page.entries())
                .extracting(entry -> entry.type().name() + ":" + entry.direction().name())
                .containsExactlyInAnyOrder(
                        "TOP_UP:CREDIT", "PAYMENT:DEBIT", "PAYMENT_CANCELLATION:CREDIT");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("FR-008: 커서로 나눠 조회해도 중복·누락이 없다")
    void cursorPaginationIsStable() {
        topUp("outbox-topup-00007", 100_000);
        pay("order-page-1", "outbox-payment-00003", 10_000);
        pay("order-page-2", "outbox-payment-00004", 10_000);
        pay("order-page-3", "outbox-payment-00005", 10_000);
        outboxPublisher.publishBatch();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(projectionCount()).isEqualTo(4L));

        WalletTransactionQuery.TransactionPage firstPage =
                walletTransactionQuery.list(memberId, walletId, null, 2);
        assertThat(firstPage.entries()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();

        WalletTransactionQuery.TransactionPage secondPage =
                walletTransactionQuery.list(memberId, walletId, firstPage.nextCursor(), 2);
        assertThat(secondPage.entries()).hasSize(2);

        List<Object> allIds = java.util.stream.Stream.concat(
                        firstPage.entries().stream(), secondPage.entries().stream())
                .map(entry -> (Object) entry.transactionId())
                .toList();
        assertThat(allIds).doesNotHaveDuplicates().hasSize(4);
    }

    private void topUp(String key, long amount) {
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId, walletId, bankAccountId, Money.krw(amount), IdempotencyKey.of(key)));
    }

    private PaymentView pay(String orderId, String key, long amount) {
        return approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                orderId,
                walletId,
                merchantId,
                Money.krw(amount),
                PaymentMethod.PAY_MONEY,
                IdempotencyKey.of(key)));
    }

    private long outboxCount(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    private List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox_event", String.class);
    }

    private String publishedEnvelopeJson(String eventType) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT event_id, event_type, event_version, aggregate_type, aggregate_id,
                       partition_key, payload::text AS payload, occurred_at
                  FROM outbox_event
                 WHERE event_type = ?
                 LIMIT 1
                """,
                eventType);
        return """
               {"eventId":"%s","eventType":"%s","eventVersion":%s,"aggregateType":"%s",
                "aggregateId":"%s","partitionKey":"%s","occurredAt":"%s","traceId":null,
                "payload":%s}
               """
                .formatted(
                        row.get("event_id"),
                        row.get("event_type"),
                        row.get("event_version"),
                        row.get("aggregate_type"),
                        row.get("aggregate_id"),
                        row.get("partition_key"),
                        ((java.sql.Timestamp) row.get("occurred_at")).toInstant(),
                        row.get("payload"));
    }

    private long projectionCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_transaction WHERE wallet_id = ?",
                Long.class,
                walletId.value());
        return count == null ? 0L : count;
    }

    private long consumedEventCountFor(String consumerName, String eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consumed_event WHERE consumer_name = ? AND event_id = ?::uuid",
                Long.class,
                consumerName,
                eventId);
        return count == null ? 0L : count;
    }

    private long consumedEventCountFor(String consumerName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consumed_event WHERE consumer_name = ?", Long.class, consumerName);
        return count == null ? 0L : count;
    }
}
