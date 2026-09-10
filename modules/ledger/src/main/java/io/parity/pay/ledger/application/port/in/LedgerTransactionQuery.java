package io.parity.pay.ledger.application.port.in;

import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 원장 거래 조회.
 *
 * <p>운영자가 "이 결제가 원장에 어떻게 적혔는가"를 확인하는 경로입니다. 항목을 계정 코드와 함께
 * 돌려주는 것이 요점입니다 — 계정 ID만으로는 화면에서 아무것도 읽을 수 없습니다.
 *
 * <p>수정 기능은 없습니다. 확정 원장은 UPDATE·DELETE가 트리거로 막혀 있고(INV-006), 조회 포트가
 * 그 사실을 흐리지 않게 합니다. 근거: docs/15-ui-screen-plan.md §4.4
 */
public interface LedgerTransactionQuery {

    Optional<LedgerTransactionView> findById(LedgerTransactionId id);

    /**
     * 원장 거래 하나입니다.
     *
     * @param balanced 차변 합계와 대변 합계가 같은지. 화면이 이것을 직접 계산하지 않게 합니다
     */
    record LedgerTransactionView(
            UUID transactionId,
            String referenceType,
            UUID referenceId,
            String transactionType,
            String currency,
            String status,
            UUID reversalOfTransactionId,
            Instant effectiveAt,
            Instant createdAt,
            List<EntryView> entries,
            Money debitTotal,
            Money creditTotal,
            boolean balanced) {}

    /**
     * 항목 하나입니다.
     *
     * @param accountCode 계정 코드. 화면에 보여 줄 수 있는 유일한 값입니다
     */
    record EntryView(UUID entryId, UUID accountId, String accountCode, UUID ownerId, String direction, Money amount) {}
}
