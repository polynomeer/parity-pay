package io.parity.pay.ledger.domain;

/**
 * 원장 거래가 가리키는 업무 참조의 종류.
 *
 * <p>ledger 모듈은 업무 모듈을 직접 참조하지 않고 이 타입과 ID만 저장합니다.
 * 근거: docs/05-technical-design.md §5
 */
public enum ReferenceType {
    TOP_UP,
    PAYMENT,
    PAYMENT_CANCELLATION,
    TRANSFER,
    WITHDRAWAL,
    SETTLEMENT,
    EXTERNAL_DEPOSIT,
    ADJUSTMENT
}
