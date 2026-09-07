package io.parity.pay.ledger.application.port.in;

import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.Money;

/**
 * 원장 항목을 재생해 계정 잔액을 계산합니다.
 *
 * <p>이 값이 진실이며 {@code wallet_balance}는 스냅샷입니다. 두 값의 일치를 정기적으로 검증합니다.
 * 근거: INV-010, ADR-008
 */
public interface LedgerBalanceQuery {

    /** 계정의 정상 잔액 방향 기준 잔액입니다. */
    Money balanceOf(LedgerAccountId accountId);
}
