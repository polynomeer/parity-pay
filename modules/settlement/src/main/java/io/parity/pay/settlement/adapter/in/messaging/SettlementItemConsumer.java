package io.parity.pay.settlement.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.parity.pay.settlement.application.port.out.SettlementItemRepository;
import io.parity.pay.settlement.application.service.SettlementProperties;
import io.parity.pay.settlement.domain.SettlementCalculator;
import io.parity.pay.settlement.domain.SettlementItem;
import io.parity.pay.settlement.domain.SettlementItem.SettlementItemStatus;
import io.parity.pay.settlement.domain.SettlementItemType;
import io.parity.pay.shared.event.ConsumedEventStore;
import io.parity.pay.shared.id.EventId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.CurrencyCode;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 항목을 만드는 소비자.
 *
 * <p>구매확정과 취소 사실을 정산 항목으로 옮깁니다. 결제 테이블을 직접 읽지 않고 이벤트만 사용합니다.
 * 근거: docs/05-technical-design.md §5, ADR-006
 *
 * <p>같은 이벤트가 다시 와도 항목은 한 번만 생깁니다. 소비 이력과 항목의 업무 유니크 키
 * ({@code item_type + source_reference_id})가 이중 방어선입니다.
 */
@Component
public class SettlementItemConsumer {

    public static final String CONSUMER_NAME = "settlement-item-builder";

    private static final Logger log = LoggerFactory.getLogger(SettlementItemConsumer.class);

    private final ConsumedEventStore consumedEventStore;
    private final SettlementItemRepository itemRepository;
    private final SettlementProperties properties;
    private final ObjectMapper objectMapper;

    public SettlementItemConsumer(
            ConsumedEventStore consumedEventStore,
            SettlementItemRepository itemRepository,
            SettlementProperties properties,
            ObjectMapper objectMapper) {
        this.consumedEventStore = consumedEventStore;
        this.itemRepository = itemRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${paritypay.events.topic:paritypay.events}",
            groupId = "${paritypay.settlement.consumer-group:paritypay-settlement}")
    @Transactional
    public void onMessage(String message) throws Exception {
        consume(objectMapper.readTree(message));
    }

    @Transactional
    public void consume(JsonNode envelope) {
        EventId eventId = EventId.of(envelope.get("eventId").asText());
        String eventType = envelope.get("eventType").asText();

        if (!consumedEventStore.markConsumed(CONSUMER_NAME, eventId)) {
            log.debug("skipping duplicate delivery of {} ({})", eventType, eventId);
            return;
        }

        JsonNode payload = envelope.get("payload");
        Instant occurredAt = Instant.parse(envelope.get("occurredAt").asText());

        switch (eventType) {
            case "OrderConfirmed" -> onOrderConfirmed(payload, occurredAt);
            case "PaymentCancellationCompleted" -> onCancellationCompleted(payload, occurredAt);
            default -> log.debug("ignoring event type {}", eventType);
        }
    }

    /** 구매확정된 금액이 판매 항목이 되고, 그에 대한 수수료 항목이 함께 생깁니다. */
    private void onOrderConfirmed(JsonNode payload, Instant occurredAt) {
        MerchantId merchantId = MerchantId.of(payload.get("merchantId").asText());
        PaymentId paymentId = PaymentId.of(payload.get("paymentId").asText());
        long settleable = payload.get("settleableAmount").asLong();
        CurrencyCode currency = CurrencyCode.valueOf(payload.get("currency").asText());

        if (settleable <= 0) {
            // 확정 시점에 이미 전액 취소된 결제입니다. 정산할 것이 없습니다.
            log.info("nothing to settle for payment {} (fully canceled before confirmation)", paymentId);
            return;
        }

        itemRepository.append(
                SettlementItem.sale(merchantId, paymentId, settleable, currency, occurredAt));

        long fee = SettlementCalculator.feeFor(settleable, properties.feeBasisPoints());
        if (fee > 0) {
            itemRepository.append(
                    SettlementItem.fee(merchantId, paymentId, fee, currency, occurredAt));
        }
    }

    /**
     * 취소는 정산에서 회수 대상입니다.
     *
     * <p>아직 지급되지 않은 회차의 결제면 취소 항목으로 차감하고, 이미 지급된 회차의 결제면 다음
     * 회차에 반영할 조정 항목을 만듭니다. 지급이 끝난 정산 자체는 고치지 않습니다.
     * 근거: docs/04-payment-policy.md §8, ADR-009
     */
    private void onCancellationCompleted(JsonNode payload, Instant occurredAt) {
        PaymentId paymentId = PaymentId.of(payload.get("paymentId").asText());
        MerchantId merchantId = MerchantId.of(payload.get("merchantId").asText());
        String cancellationId = payload.get("cancellationId").asText();
        long amount = payload.get("amount").asLong();
        CurrencyCode currency = CurrencyCode.valueOf(payload.get("currency").asText());

        List<SettlementItem> existing = itemRepository.findByPaymentId(paymentId);
        if (existing.isEmpty()) {
            // 구매확정 전에 취소된 결제입니다. 정산 대상이 된 적이 없습니다.
            log.debug("no settlement items for payment {}; nothing to reverse", paymentId);
            return;
        }

        boolean alreadySettled = existing.stream()
                .anyMatch(item -> item.type() == SettlementItemType.SALE
                        && item.status() == SettlementItemStatus.SETTLED);

        if (alreadySettled) {
            itemRepository.append(SettlementItem.adjustment(
                    merchantId, paymentId, cancellationId, -amount, currency, occurredAt));
        } else {
            itemRepository.append(SettlementItem.cancellation(
                    merchantId, paymentId, cancellationId, amount, currency, occurredAt));
        }

        // 취소된 금액에 대한 수수료는 판매자에게 돌려줍니다.
        long feeRefund = SettlementCalculator.feeFor(amount, properties.feeBasisPoints());
        if (feeRefund > 0) {
            itemRepository.append(SettlementItem.adjustment(
                    merchantId,
                    paymentId,
                    cancellationId + ":FEE_REFUND",
                    feeRefund,
                    currency,
                    occurredAt));
        }
    }
}
