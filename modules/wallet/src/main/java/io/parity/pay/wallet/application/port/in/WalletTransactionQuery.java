package io.parity.pay.wallet.application.port.in;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.wallet.domain.WalletTransactionEntry;
import java.util.List;

/**
 * 지갑 거래내역 조회. 근거: FR-008
 *
 * <p>offset 대신 커서를 사용합니다. 조회 중에 새 거래가 생겨도 중복·누락이 생기지 않습니다.
 * 근거: docs/08-db-api-event-spec.md §9
 */
public interface WalletTransactionQuery {

    TransactionPage list(MemberId memberId, WalletId walletId, String cursor, int limit);

    record TransactionPage(List<WalletTransactionEntry> entries, String nextCursor) {}
}
