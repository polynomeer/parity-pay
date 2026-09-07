package io.parity.pay.wallet.application.event;

import io.parity.pay.shared.event.EventEnvelope;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.wallet.domain.TopUp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지갑 도메인 이벤트.
 *
 * <p>소비자가 필요로 하는 안정된 사실만 담고 내부 엔티티를 통째로 직렬화하지 않습니다. 계좌번호와
 * 멱등 키 원문은 넣지 않습니다. 근거: docs/08-db-api-event-spec.md §6·§7
 */
public final class WalletEvents {

    public static final String WALLET_CREATED = "WalletCreated";
    public static final String TOP_UP_COMPLETED = "TopUpCompleted";

    private WalletEvents() {}

    public static EventEnvelope walletCreated(
            WalletId walletId, MemberId memberId, CurrencyCode currency, java.time.Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletId", walletId.toString());
        payload.put("memberId", memberId.toString());
        payload.put("currency", currency.name());
        return EventEnvelope.of(
                WALLET_CREATED, 1, "Wallet", walletId.toString(), occurredAt, null, payload);
    }

    public static EventEnvelope topUpCompleted(TopUp topUp, LedgerTransactionId ledgerTransactionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topUpId", topUp.id().toString());
        payload.put("walletId", topUp.walletId().toString());
        payload.put("amount", topUp.completedAmount().amount());
        payload.put("currency", topUp.completedAmount().currency().name());
        payload.put("ledgerTransactionId", ledgerTransactionId.toString());
        return EventEnvelope.of(
                TOP_UP_COMPLETED,
                1,
                "TopUp",
                topUp.walletId().toString(),
                topUp.completedAt(),
                null,
                payload);
    }
}
