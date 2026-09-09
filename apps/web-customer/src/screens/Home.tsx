/**
 * SCR-03 홈 — 잔액 (FR-004, FE-005).
 *
 * 잔액은 원장이 아니라 **조회 스냅샷**입니다(ADR-008). 그래서 두 가지를 지킵니다.
 *
 * - `asOf`를 함께 보여 줍니다. 언제 기준인지 밝히지 않은 잔액은 오해를 만듭니다.
 * - 클라이언트에서 잔액을 계산하지 않습니다. 결제 후 `available - amount`를 그려 두면 동시
 *   거래가 있을 때 틀립니다. 다시 조회합니다.
 */
import { useQuery } from "@tanstack/react-query";
import { getMyWallet, type WalletBalanceResponse } from "@paritypay/api-client";
import { api } from "../api";
import { formatInstant, formatWon } from "../format";

export function Home() {
  const wallet = useQuery<WalletBalanceResponse>({
    queryKey: ["wallet", "me"],
    queryFn: () => getMyWallet(api),
  });

  if (wallet.isPending) {
    return <p>잔액을 불러오는 중입니다.</p>;
  }
  if (wallet.isError) {
    return <p role="alert">잔액을 불러오지 못했습니다.</p>;
  }

  const { available, pending, asOf } = wallet.data;
  return (
    <section>
      <h1>페이머니</h1>
      <p data-testid="available">{formatWon(available ?? 0)}</p>
      {(pending ?? 0) > 0 && <p data-testid="pending">보류 {formatWon(pending ?? 0)}</p>}
      {/* 스냅샷 기준 시각입니다. 숨기지 않습니다. */}
      <p data-testid="as-of">{asOf === undefined ? "" : `${formatInstant(asOf)} 기준`}</p>
    </section>
  );
}
