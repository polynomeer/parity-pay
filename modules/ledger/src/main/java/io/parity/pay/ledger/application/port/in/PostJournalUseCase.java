package io.parity.pay.ledger.application.port.in;

import io.parity.pay.ledger.domain.Journal;
import io.parity.pay.ledger.domain.LedgerTransaction;

/**
 * 분개를 원장에 전기합니다.
 *
 * <p>같은 {@code (referenceType, referenceId, transactionType)}으로 여러 번 호출해도 POSTED 거래는
 * 한 건만 생성되고, 이후 호출은 기존 거래를 반환합니다. 근거: INV-004
 *
 * <p>호출자는 반드시 자신의 업무 트랜잭션 안에서 호출해야 합니다.
 * 근거: docs/05-technical-design.md §7
 */
public interface PostJournalUseCase {

    LedgerTransaction post(Journal journal);
}
