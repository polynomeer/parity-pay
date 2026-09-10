import { getMyWallet } from "@paritypay/api-client";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { Transactions } from "./Transactions";

export function TransactionsRoute() {
  const wallet = useQuery({ queryKey: ["wallet", "me"], queryFn: () => getMyWallet(api) });
  if (wallet.data?.walletId === undefined) {
    return <p>불러오는 중입니다.</p>;
  }
  return <Transactions walletId={wallet.data.walletId} />;
}
