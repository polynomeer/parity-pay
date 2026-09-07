package io.parity.pay.wallet.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import java.time.Instant;
import java.util.Objects;

/**
 * 지갑. 잔액은 {@link WalletBalance} 투영으로 분리되어 있고, 이 Aggregate는 소유권과 상태를 관리합니다.
 *
 * <p>근거: docs/06-domain-state-design.md §2
 */
public record Wallet(WalletId id, MemberId memberId, CurrencyCode currency, WalletStatus status, Instant createdAt) {

    public Wallet {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static Wallet open(MemberId memberId, CurrencyCode currency, Instant now) {
        return new Wallet(WalletId.generate(), memberId, currency, WalletStatus.ACTIVE, now);
    }

    /** 충전할 수 없는 상태이면 예외를 던집니다. 근거: docs/04-payment-policy.md §3 */
    public void requireTopUpAllowed() {
        if (!status.canTopUp()) {
            throw new BusinessException(ErrorCode.WALLET_NOT_ACTIVE, "wallet is not active: " + status);
        }
    }

    /** 지출할 수 없는 상태이면 예외를 던집니다. 근거: docs/04-payment-policy.md §2 */
    public void requireSpendAllowed() {
        if (!status.canSpend()) {
            throw new BusinessException(ErrorCode.WALLET_NOT_ACTIVE, "wallet is not active: " + status);
        }
    }

    public void requireCurrency(CurrencyCode expected) {
        if (currency != expected) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "wallet currency " + currency + " does not match request currency " + expected);
        }
    }

    public void requireOwnedBy(MemberId candidate) {
        if (!memberId.equals(candidate)) {
            // 소유자가 아니면 존재 여부 자체를 노출하지 않습니다.
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "wallet not found");
        }
    }
}
