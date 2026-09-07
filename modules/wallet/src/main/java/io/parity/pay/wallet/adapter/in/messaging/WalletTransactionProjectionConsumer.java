package io.parity.pay.wallet.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.parity.pay.shared.event.ConsumedEventStore;
import io.parity.pay.shared.id.EventId;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.event.WalletEvents;
import io.parity.pay.wallet.application.port.out.WalletTransactionRepository;
import io.parity.pay.wallet.domain.WalletTransactionEntry;
import io.parity.pay.wallet.domain.WalletTransactionEntry.TransactionDirection;
import io.parity.pay.wallet.domain.WalletTransactionEntry.WalletTransactionType;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래내역 프로젝션 소비자.
 *
 * <p>at-least-once 전달을 전제로 합니다. 소비 기록과 프로젝션 쓰기가 하나의 로컬 트랜잭션이므로,
 * 커밋 후 ACK가 유실되어 같은 이벤트가 다시 와도 결과가 두 번 생기지 않습니다.
 * 근거: ADR-006, docs/09-consistency-recovery.md §6
 */
@Component
public class WalletTransactionProjectionConsumer {

    /** 소비 이력의 주체 이름입니다. 바꾸면 과거 이벤트를 다시 소비하게 되므로 신중히 변경합니다. */
    public static final String CONSUMER_NAME = "wallet-transaction-projection";

    private static final Logger log = LoggerFactory.getLogger(WalletTransactionProjectionConsumer.class);

    private final ConsumedEventStore consumedEventStore;
    private final WalletTransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    public WalletTransactionProjectionConsumer(
            ConsumedEventStore consumedEventStore,
            WalletTransactionRepository transactionRepository,
            ObjectMapper objectMapper) {
        this.consumedEventStore = consumedEventStore;
        this.transactionRepository = transactionRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paritypay.events.topic:paritypay.events}",
            groupId = "${paritypay.events.consumer-group:paritypay-wallet-projection}")
    @Transactional
    public void onMessage(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        consume(envelope);
    }

    /** 브로커 없이도 같은 경로를 검증할 수 있도록 봉투 처리를 분리해 둡니다. */
    @Transactional
    public void consume(JsonNode envelope) {
        EventId eventId = EventId.of(envelope.get("eventId").asText());
        String eventType = envelope.get("eventType").asText();

        // 1. 이 소비자가 이미 처리한 이벤트면 아무것도 하지 않고 성공으로 끝냅니다.
        if (!consumedEventStore.markConsumed(CONSUMER_NAME, eventId)) {
            log.debug("skipping duplicate delivery of {} ({})", eventType, eventId);
            return;
        }

        JsonNode payload = envelope.get("payload");
        Instant occurredAt = Instant.parse(envelope.get("occurredAt").asText());

        switch (eventType) {
            case WalletEvents.TOP_UP_COMPLETED -> append(
                    payload,
                    occurredAt,
                    WalletTransactionType.TOP_UP,
                    TransactionDirection.CREDIT,
                    "TOP_UP",
                    payload.get("topUpId").asText());
            case "PaymentApproved" -> append(
                    payload,
                    occurredAt,
                    WalletTransactionType.PAYMENT,
                    TransactionDirection.DEBIT,
                    "PAYMENT",
                    payload.get("paymentId").asText());
            case "PaymentCancellationCompleted" -> append(
                    payload,
                    occurredAt,
                    WalletTransactionType.PAYMENT_CANCELLATION,
                    TransactionDirection.CREDIT,
                    "PAYMENT_CANCELLATION",
                    payload.get("cancellationId").asText());
            default ->
            // 이 프로젝션이 관심 없는 이벤트입니다. 소비 기록만 남기고 넘어갑니다.
            log.debug("ignoring event type {}", eventType);
        }
    }

    private void append(
            JsonNode payload,
            Instant occurredAt,
            WalletTransactionType type,
            TransactionDirection direction,
            String referenceType,
            String referenceId) {
        WalletTransactionEntry entry = new WalletTransactionEntry(
                UUID.randomUUID(),
                WalletId.of(payload.get("walletId").asText()),
                type,
                direction,
                Money.of(
                        payload.get("amount").asLong(),
                        CurrencyCode.valueOf(payload.get("currency").asText())),
                referenceType,
                referenceId,
                payload.hasNonNull("ledgerTransactionId")
                        ? LedgerTransactionId.of(
                                payload.get("ledgerTransactionId").asText())
                        : null,
                occurredAt);

        if (!transactionRepository.append(entry)) {
            // 소비 이력이 없어도 업무 유니크 키가 중복을 막습니다.
            log.debug("transaction entry already exists for {} {}", referenceType, referenceId);
        }
    }
}
