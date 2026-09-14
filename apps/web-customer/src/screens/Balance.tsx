/**
 * 잔액 카드입니다. 홈과 충전 화면이 같이 씁니다.
 *
 * 잔액은 원장이 아니라 **조회 스냅샷**입니다(ADR-008). 그래서 `asOf`를 함께 보여 줍니다 —
 * 언제 기준인지 밝히지 않은 잔액은 오해를 만듭니다(FE-005). 클라이언트에서 잔액을 계산하지
 * 않습니다.
 */
import type { WalletBalanceResponse } from "@paritypay/api-client";
import type { ReactNode } from "react";
import { formatInstant, formatWon } from "../format";

export function Balance({
  wallet,
  actions,
}: {
  wallet: WalletBalanceResponse;
  actions?: ReactNode;
}) {
  const { available, pending, asOf } = wallet;
  return (
    <div className="balance">
      <span className="balance__label">페이머니 잔액</span>
      <p className="balance__amount" data-testid="available">
        {formatWon(available ?? 0)}
      </p>
      {(pending ?? 0) > 0 && (
        <p className="balance__pending" data-testid="pending">
          처리 중 {formatWon(pending ?? 0)}
        </p>
      )}
      {/* 스냅샷 기준 시각입니다. 숨기지 않습니다. */}
      <p className="balance__asof" data-testid="as-of">
        {asOf === undefined ? "" : `${formatInstant(asOf)} 기준`}
      </p>
      {actions !== undefined && <div className="balance__actions">{actions}</div>}
    </div>
  );
}
