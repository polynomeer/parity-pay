/**
 * My Pay 영역의 조립 화면입니다 (DOC-15 §1.1).
 *
 * 충전과 결제는 계좌·지갑이 있어야 하므로, 없으면 먼저 연결하게 합니다.
 */
import { useQuery } from "@tanstack/react-query";
import { getMyWallet, type WalletBalanceResponse } from "@paritypay/api-client";
import { useState } from "react";
import { api } from "../api";
import { formatInstant, formatWon } from "../format";
import { TopUp } from "./TopUp";
import { LinkBankAccount } from "./LinkBankAccount";

export function Pay() {
  const [bankAccountId, setBankAccountId] = useState<string | null>(null);
  const wallet = useQuery<WalletBalanceResponse>({
    queryKey: ["wallet", "me"],
    queryFn: () => getMyWallet(api),
  });

  if (wallet.isPending) {
    return <p>지갑을 불러오는 중입니다.</p>;
  }
  if (wallet.isError) {
    return <p role="alert">지갑을 불러오지 못했습니다.</p>;
  }

  return (
    <section>
      <h1>페이머니</h1>
      <p data-testid="available">{formatWon(wallet.data.available ?? 0)}</p>
      {(wallet.data.pending ?? 0) > 0 && (
        <p data-testid="pending">처리 중 {formatWon(wallet.data.pending ?? 0)}</p>
      )}
      <p data-testid="as-of">
        {wallet.data.asOf === undefined ? "" : `${formatInstant(wallet.data.asOf)} 기준`}
      </p>

      {bankAccountId === null ? (
        <LinkBankAccount onLinked={setBankAccountId} />
      ) : (
        <TopUp walletId={wallet.data.walletId!} bankAccountId={bankAccountId} />
      )}
    </section>
  );
}
