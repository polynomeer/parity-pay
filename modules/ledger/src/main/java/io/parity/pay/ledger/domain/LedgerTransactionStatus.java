package io.parity.pay.ledger.domain;

/**
 * 원장 거래 상태.
 *
 * <p>{@code REVERSED}는 원거래가 수정됐다는 뜻이 아니라 별도 역분개가 연결됐다는 표시입니다.
 * 근거: docs/07-ledger-journal-catalog.md §4, ADR-009
 */
public enum LedgerTransactionStatus {
    DRAFT,
    POSTED,
    REVERSED
}
