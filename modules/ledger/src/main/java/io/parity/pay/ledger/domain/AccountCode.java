package io.parity.pay.ledger.domain;

/**
 * 원장 계정 체계.
 *
 * <p>여기 없는 계정을 코드에서 임의로 만들지 않습니다. 새 계정이 필요하면
 * docs/07-ledger-journal-catalog.md §3을 먼저 갱신합니다.
 */
public enum AccountCode {
    /** 1010 은행 예치금 */
    BANK_DEPOSIT("1010", AccountClass.ASSET, OwnerType.CORPORATE),
    /** 1020 PG 미수금 */
    PG_RECEIVABLE("1020", AccountClass.ASSET, OwnerType.PROVIDER),
    /** 1030 판매자 미수금 */
    MERCHANT_RECEIVABLE("1030", AccountClass.ASSET, OwnerType.MERCHANT),
    /** 2010 사용자 페이머니 */
    USER_PAY_MONEY("2010", AccountClass.LIABILITY, OwnerType.WALLET),
    /** 2020 결제 보류금 */
    PAYMENT_HOLDING("2020", AccountClass.LIABILITY, OwnerType.WALLET),
    /** 2030 판매자 지급예정금 */
    MERCHANT_PAYABLE("2030", AccountClass.LIABILITY, OwnerType.MERCHANT),
    /** 2040 포인트 충당부채 */
    POINT_LIABILITY("2040", AccountClass.LIABILITY, OwnerType.WALLET),
    /** 2050 미확인 입금 */
    UNIDENTIFIED_DEPOSIT("2050", AccountClass.LIABILITY, OwnerType.EXTERNAL),
    /** 3010 자본·초기조정 */
    EQUITY_ADJUSTMENT("3010", AccountClass.EQUITY, OwnerType.CORPORATE),
    /** 4010 플랫폼 수수료 수익 */
    PLATFORM_FEE_REVENUE("4010", AccountClass.REVENUE, OwnerType.CORPORATE),
    /** 5010 PG·은행 수수료 비용 */
    PROVIDER_FEE_EXPENSE("5010", AccountClass.EXPENSE, OwnerType.PROVIDER),
    /** 9010 정산 조정 계정 */
    SETTLEMENT_CLEARING("9010", AccountClass.CLEARING, OwnerType.ADJUSTMENT);

    private final String code;
    private final AccountClass accountClass;
    private final OwnerType ownerType;

    AccountCode(String code, AccountClass accountClass, OwnerType ownerType) {
        this.code = code;
        this.accountClass = accountClass;
        this.ownerType = ownerType;
    }

    public String code() {
        return code;
    }

    public AccountClass accountClass() {
        return accountClass;
    }

    public OwnerType ownerType() {
        return ownerType;
    }

    public Direction normalBalance() {
        return accountClass.normalBalance();
    }

    public static AccountCode fromCode(String code) {
        for (AccountCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown account code: " + code);
    }
}
