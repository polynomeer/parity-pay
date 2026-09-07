package io.parity.pay.wallet.application.port.in;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;

/**
 * 다른 업무 모듈이 지갑 잔액을 움직일 때 사용하는 포트.
 *
 * <p>호출자는 반드시 자신의 업무 트랜잭션 안에서 호출해야 합니다. 잔액 변경은 업무 상태·원장과 같은
 * 트랜잭션에서 커밋됩니다. 근거: docs/05-technical-design.md §7, ADR-004
 */
public interface WalletFundsUseCase {

    /** 지출 가능한 지갑인지 확인합니다. 소유자가 아니면 존재 여부를 노출하지 않고 404입니다. */
    SpendableWallet requireSpendable(WalletId walletId, MemberId memberId, CurrencyCode currency);

    /**
     * 가용 잔액이 충분할 때만 차감합니다.
     *
     * <p>조회 후 계산 후 저장이 아니라 조건부 단일 UPDATE입니다. 잔액이 부족하면
     * {@code INSUFFICIENT_BALANCE}입니다. 근거: INV-003, AC-003
     */
    void debit(WalletId walletId, Money amount);

    /** 환불·취소로 잔액을 되돌립니다. */
    void credit(WalletId walletId, Money amount);

    /** 지갑 Aggregate 전체를 노출하지 않고 결제가 필요한 사실만 전달합니다. */
    record SpendableWallet(WalletId walletId, MemberId memberId, CurrencyCode currency) {}
}
