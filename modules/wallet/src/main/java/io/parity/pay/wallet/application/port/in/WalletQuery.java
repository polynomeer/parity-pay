package io.parity.pay.wallet.application.port.in;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;

/** 지갑 잔액 조회. 근거: FR-004 */
public interface WalletQuery {

    WalletBalanceView getBalance(MemberId memberId, WalletId walletId);

    /**
     * 호출자 자신의 지갑 잔액입니다.
     *
     * <p>클라이언트가 `walletId`를 들고 다니지 않아도 되게 합니다. 지금까지 `walletId`는 가입
     * 응답에만 있었고 토큰에도 들어 있지 않아, 다른 기기에서 로그인하면 자기 지갑을 찾을 방법이
     * 없었습니다. 프론트엔드를 붙이면서 드러난 공백입니다.
     * 근거: FR-004, docs/14-frontend-design.md §13 열린 질문 5
     */
    WalletBalanceView getMyBalance(MemberId memberId, CurrencyCode currency);

    /**
     * 스냅샷과 원장 재생값을 비교합니다. 근거: INV-010, docs/09-consistency-recovery.md §8
     * (BalanceVerifier)
     */
    BalanceVerification verifyAgainstLedger(WalletId walletId);

    record WalletBalanceView(WalletId walletId, Money available, Money pending, Instant asOf) {}

    record BalanceVerification(WalletId walletId, Money snapshot, Money ledger, boolean matches) {}
}
