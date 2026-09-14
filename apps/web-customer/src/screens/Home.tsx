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
import { Link } from "react-router-dom";
import { api } from "../api";
import { Balance } from "./Balance";

const SHORTCUTS = [
  { to: "/pay", icon: "＋", label: "충전", hint: "계좌에서 페이머니로" },
  { to: "/shop", icon: "🛍", label: "상품", hint: "결제 데모" },
  { to: "/orders", icon: "📦", label: "주문", hint: "취소·구매확정" },
  { to: "/transactions", icon: "☰", label: "거래내역", hint: "원장 기준 기록" },
];

export function Home() {
  const wallet = useQuery<WalletBalanceResponse>({
    queryKey: ["wallet", "me"],
    queryFn: () => getMyWallet(api),
  });

  if (wallet.isPending) {
    return <p className="loading">잔액을 불러오는 중입니다.</p>;
  }
  if (wallet.isError) {
    return <p role="alert">잔액을 불러오지 못했습니다.</p>;
  }

  return (
    <section className="page">
      <h1>페이머니</h1>
      <Balance
        wallet={wallet.data}
        actions={
          <>
            <Link to="/pay" className="btn btn--primary">
              충전하기
            </Link>
            <Link to="/transactions" className="btn">
              거래내역
            </Link>
          </>
        }
      />
      <nav className="quick" aria-label="바로가기">
        {SHORTCUTS.map((item) => (
          <Link key={item.to} to={item.to}>
            <span className="quick__icon" aria-hidden="true">
              {item.icon}
            </span>
            {item.label}
            <small>{item.hint}</small>
          </Link>
        ))}
      </nav>
    </section>
  );
}
