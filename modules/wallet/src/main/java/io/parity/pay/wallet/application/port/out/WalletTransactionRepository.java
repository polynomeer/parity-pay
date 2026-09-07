package io.parity.pay.wallet.application.port.out;

import io.parity.pay.shared.id.WalletId;
import io.parity.pay.wallet.domain.WalletTransactionEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WalletTransactionRepository {

    /**
     * 거래내역을 추가합니다. 같은 업무 참조가 이미 있으면 아무것도 하지 않습니다.
     *
     * <p>소비 이력({@code consumed_event})과 함께 쓰는 두 번째 방어선입니다. 소비 이력이 지워지거나
     * 소비자 이름이 바뀌어도 내역이 두 줄이 되지 않습니다. 근거: ADR-006, INV-004
     *
     * @return 실제로 추가되었으면 {@code true}
     */
    boolean append(WalletTransactionEntry entry);

    /** 커서 기반 조회입니다. 금액 변동 중에도 중복·누락이 생기지 않습니다. */
    List<WalletTransactionEntry> findPage(
            WalletId walletId, Instant beforeOccurredAt, UUID beforeTransactionId, int limit);
}
