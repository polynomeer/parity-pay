package io.parity.pay.wallet.application.port.in;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;

/** 지갑 잔액 조회. 근거: FR-004 */
public interface WalletQuery {

    WalletBalanceView getBalance(MemberId memberId, WalletId walletId);

    /**
     * 스냅샷과 원장 재생값을 비교합니다. 근거: INV-010, docs/09-consistency-recovery.md §8
     * (BalanceVerifier)
     */
    BalanceVerification verifyAgainstLedger(WalletId walletId);

    record WalletBalanceView(WalletId walletId, Money available, Money pending, Instant asOf) {}

    record BalanceVerification(WalletId walletId, Money snapshot, Money ledger, boolean matches) {}
}
