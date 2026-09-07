package io.parity.pay.wallet.application.port.in;

import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.Money;

/**
 * 잔액 스냅샷을 원장으로 재구축합니다.
 *
 * <p>원장이 진실이고 스냅샷은 파생입니다(ADR-008). 둘이 어긋나면 고칠 대상은 스냅샷이며, 원장은
 * 손대지 않습니다. 확정 원장을 고치는 방법은 역분개·보정 분개뿐입니다(INV-006, ADR-009).
 *
 * <p>재구축은 임의 금액을 쓰지 않습니다. 쓸 수 있는 값은 원장에서 계산한 값 하나뿐이므로, 이
 * 경로로는 없는 돈을 만들 수 없습니다.
 *
 * <p>자동으로 돌지 않습니다. 스냅샷이 어긋났다는 것은 어딘가 잘못됐다는 뜻인데, 배경 작업이 조용히
 * 맞춰버리면 원인을 조사할 증거가 사라집니다. 사람이 사유를 적고 승인을 받아 실행합니다.
 *
 * <p>근거: INV-010, docs/09-consistency-recovery.md §13, reports/11 F-010
 */
public interface RebuildBalanceUseCase {

    RebuildOutcome rebuildFromLedger(WalletId walletId);

    /**
     * 재구축 결과.
     *
     * @param snapshotBefore 실행 전 스냅샷의 원장 대응값(가용 + 처리중)
     * @param ledger 같은 시점 원장 계산값
     * @param snapshotAfter 실행 후 스냅샷의 원장 대응값
     */
    record RebuildOutcome(
            WalletId walletId,
            Money snapshotBefore,
            Money ledger,
            Money snapshotAfter,
            RebuildStatus status,
            String detail) {

        public boolean changed() {
            return status == RebuildStatus.REBUILT;
        }
    }

    enum RebuildStatus {
        /** 스냅샷과 원장이 이미 같습니다. 아무것도 쓰지 않았습니다. */
        ALREADY_CONSISTENT,
        /** 스냅샷을 원장 계산값으로 되돌렸습니다. */
        REBUILT,
        /**
         * 되돌리지 않았습니다. 원장 잔액이 처리중 금액보다 작아 가용 잔액을 음수로 만들지 않고는
         * 맞출 수 없거나(INV-003), 실행 도중 스냅샷이 다른 트랜잭션에 의해 바뀌었습니다.
         */
        REFUSED
    }
}
