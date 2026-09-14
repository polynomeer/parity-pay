/**
 * 운영 콘솔의 틀입니다 — 왼쪽 메뉴, 오른쪽 화면. 로그인 화면은 이 틀 밖에 있습니다.
 */
import { NavLink, Outlet } from "react-router-dom";
import { tokenStore } from "./api";

const MENU = [
  { to: "/", icon: "⌕", label: "거래 검색", end: true },
  { to: "/unresolved", icon: "◔", label: "미확정 거래" },
  { to: "/reconciliation", icon: "⇄", label: "대사" },
  { to: "/lab", icon: "⚡", label: "장애 시뮬레이터" },
] as const;

const ROLE_LABEL: Record<string, string> = {
  OPS_VIEWER: "조회",
  OPS_OPERATOR: "운영",
  OPS_APPROVER: "승인",
};

export function Shell() {
  const tokens = tokenStore.read();
  const roles = (tokens?.roles ?? []).filter((role) => role in ROLE_LABEL);

  return (
    <div className="ops">
      <aside className="sidebar">
        <NavLink to="/" className="brand">
          <span className="brand__mark" aria-hidden="true">
            P
          </span>
          ParityPay
          <span className="sidebar__tag">OPS</span>
        </NavLink>
        <nav className="sidenav" aria-label="주 메뉴">
          {MENU.map((item) => (
            <NavLink key={item.to} to={item.to} end={"end" in item}>
              <span className="sidenav__icon" aria-hidden="true">
                {item.icon}
              </span>
              {item.label}
            </NavLink>
          ))}
        </nav>
        {tokens !== null && (
          <div className="sidebar__foot">
            <span className="id">{tokens.memberId}</span>
            <span className="row">
              {roles.map((role) => (
                <span key={role} className="badge">
                  {ROLE_LABEL[role]}
                </span>
              ))}
            </span>
          </div>
        )}
      </aside>
      <main className="ops-main">
        <Outlet />
      </main>
    </div>
  );
}
