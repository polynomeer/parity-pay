package io.parity.pay.wallet.application.port.in;

import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.domain.TopUp;
import io.parity.pay.wallet.domain.TopUpStatus;
import java.time.Instant;

/**
 * 페이머니 충전. 근거: FR-003, docs/04-payment-policy.md §3
 *
 * <p>같은 {@code idempotencyKey}로 반복 호출해도 금액은 한 번만 이동합니다.
 */
public interface RequestTopUpUseCase {

    TopUpView requestTopUp(TopUpCommand command);

    TopUpView getTopUp(MemberId memberId, TopUpId topUpId);

    record TopUpCommand(
            MemberId memberId,
            WalletId walletId,
            BankAccountId bankAccountId,
            Money amount,
            IdempotencyKey idempotencyKey) {}

    record TopUpView(
            TopUpId topUpId,
            TopUpStatus status,
            Money requestedAmount,
            Money completedAmount,
            Instant requestedAt,
            Instant completedAt) {

        public static TopUpView of(TopUp topUp) {
            return new TopUpView(
                    topUp.id(),
                    topUp.status(),
                    topUp.requestedAmount(),
                    topUp.completedAmount(),
                    topUp.requestedAt(),
                    topUp.completedAt());
        }
    }
}
