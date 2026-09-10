/** 지갑 ID를 알아낸 뒤 주문 상세를 그립니다. 클라이언트는 지갑 ID를 보관하지 않습니다. */
import { getMyWallet } from "@paritypay/api-client";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { OrderDetail } from "./OrderDetail";

export function OrderDetailRoute() {
  const wallet = useQuery({ queryKey: ["wallet", "me"], queryFn: () => getMyWallet(api) });
  if (wallet.data?.walletId === undefined) {
    return <p>불러오는 중입니다.</p>;
  }
  return <OrderDetail walletId={wallet.data.walletId} />;
}
