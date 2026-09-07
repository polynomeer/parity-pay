package io.parity.pay.wallet.application.port.out;

import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.domain.WalletBalance;
import java.util.Optional;

/**
 * 잔액 스냅샷 저장소.
 *
 * <p>증감은 조건과 버전을 포함한 단일 UPDATE로 수행합니다. 조회 후 계산 후 저장하지 않습니다.
 * 근거: ADR-004, docs/09-consistency-recovery.md §4
 */
public interface WalletBalanceRepository {

    Optional<WalletBalance> findByWalletId(WalletId walletId);

    WalletBalance create(WalletId walletId, Money zero);

    /**
     * 가용 잔액을 증가시킵니다.
     *
     * @return 갱신된 행 수. 0이면 지갑 잔액 행이 없다는 뜻입니다.
     */
    int increaseAvailable(WalletId walletId, Money amount);

    /**
     * 스냅샷의 가용 잔액을 주어진 값으로 되돌립니다. 재구축 전용입니다.
     *
     * <p>증감이 아니라 대입이므로 업무 경로에서 쓰면 안 됩니다. 호출자는 원장에서 계산한 값만
     * 넘겨야 하며, 그 규칙은 {@code RebuildBalanceUseCase} 구현이 지킵니다.
     *
     * <p>{@code expectedVersion}으로 낙관적 잠금을 겁니다. 읽은 뒤 쓰기 전에 정상 업무가 잔액을
     * 바꿨다면 그 결과를 덮어써서는 안 됩니다.
     *
     * @return 갱신된 행 수. 0이면 버전이 달라졌다는 뜻이며 재구축을 다시 시도해야 합니다.
     */
    int restoreAvailable(WalletId walletId, Money available, long expectedVersion);

    /**
     * 가용 잔액이 충분할 때만 차감합니다.
     *
     * @return 갱신된 행 수. 0이면 잔액 부족 또는 경합입니다. 근거: INV-003
     */
    int decreaseAvailableIfSufficient(WalletId walletId, Money amount);
}
