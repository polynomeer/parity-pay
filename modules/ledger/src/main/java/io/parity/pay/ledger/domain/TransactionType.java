package io.parity.pay.ledger.domain;

/**
 * 분개 카탈로그의 거래 유형. 근거: docs/07-ledger-journal-catalog.md §5
 *
 * <p>{@code (referenceType, referenceId, transactionType)}이 업무 유니크 키이며 INV-004의 최종
 * 방어선입니다.
 */
public enum TransactionType {
    /** JE-001 페이머니 충전 */
    TOP_UP_COMPLETED,
    /** JE-002 충전 취소 */
    TOP_UP_REVERSED,
    /** JE-003 페이머니 결제 승인 */
    PAYMENT_APPROVED,
    /** JE-004 결제 취소 */
    PAYMENT_CANCELED,
    /** JE-005 사용자 간 송금 */
    TRANSFER_COMPLETED,
    /** JE-006 사용자 출금 */
    WITHDRAWAL_COMPLETED,
    /** JE-007 수수료 인식 */
    FEE_RECOGNIZED,
    /** JE-008 판매자 정산 지급 */
    SETTLEMENT_PAID,
    /** JE-009 정산 후 환불 */
    REFUND_AFTER_SETTLEMENT,
    /** JE-010 외부 수수료 */
    EXTERNAL_FEE,
    /** JE-011 미확인 외부 입금 */
    UNIDENTIFIED_DEPOSIT_RECEIVED,
    /** JE-012 운영 보정 */
    OPERATIONAL_ADJUSTMENT
}
