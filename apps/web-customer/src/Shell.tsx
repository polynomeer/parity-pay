/**
 * 로그인한 뒤의 화면 틀입니다 — 상단 바와 메뉴, 그 아래 화면.
 *
 * 메뉴는 **링크만** 씁니다. 버튼을 두지 않는 이유가 있습니다: E2E가 상품 화면에서 "첫 번째
 * 버튼"을 구매 버튼으로 집는데, 메뉴에 버튼이 있으면 그것이 먼저 잡힙니다. 링크는 그 계약을
 * 건드리지 않습니다.
 */
import { NavLink, Outlet } from "react-router-dom";
import { tokenStore } from "./api";

const MENU = [
  { to: "/", label: "홈", end: true },
  { to: "/shop", label: "상품" },
  { to: "/orders", label: "주문" },
  { to: "/pay", label: "충전" },
  { to: "/transactions", label: "거래내역" },
] as const;

export function Shell() {
  // 정산은 판매자 역할에만 열려 있습니다(서버가 막습니다). 들어갈 수 없는 메뉴는 보이지 않습니다.
  const isMerchant = tokenStore.read()?.roles.includes("MERCHANT") ?? false;

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar__inner">
          <NavLink to="/" className="brand">
            <span className="brand__mark" aria-hidden="true">
              P
            </span>
            ParityPay
          </NavLink>
          <nav className="topnav" aria-label="주 메뉴">
            {MENU.map((item) => (
              <NavLink key={item.to} to={item.to} end={"end" in item}>
                {item.label}
              </NavLink>
            ))}
            {isMerchant && <NavLink to="/settlements">정산</NavLink>}
          </nav>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}

/** 로그인·가입·비밀번호 화면의 틀입니다. 메뉴가 없고 카드 하나가 가운데 놓입니다. */
export function AuthShell() {
  return (
    <div className="auth">
      <div className="auth__card">
        <NavLink to="/login" className="brand">
          <span className="brand__mark" aria-hidden="true">
            P
          </span>
          ParityPay
        </NavLink>
        <Outlet />
      </div>
    </div>
  );
}
