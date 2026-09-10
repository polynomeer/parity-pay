import { Link, Navigate, Route, Routes } from "react-router-dom";
import { Explorer } from "./screens/Explorer";
import { Unresolved } from "./screens/Unresolved";
import { Lab } from "./screens/Lab";
import { Reconciliation } from "./screens/Reconciliation";
import { Login } from "./screens/Login";
import { tokenStore } from "./api";

function RequireOps({ children }: { children: React.ReactNode }) {
  const tokens = tokenStore.read();
  // 운영 콘솔은 OPS 역할이 있어야 합니다. 서버도 막지만, 없는 사람에게 화면을 보여 줄 이유가 없습니다.
  const isOps = tokens?.roles.some((role) => role.startsWith("OPS_")) ?? false;
  return isOps ? <>{children}</> : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <>
      <nav>
        <Link to="/">거래 검색</Link> <Link to="/unresolved">미확정 거래</Link> <Link to="/lab">장애 시뮬레이터</Link> <Link to="/reconciliation">대사</Link>
      </nav>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          path="/"
          element={
            <RequireOps>
              <Explorer />
            </RequireOps>
          }
        />
        <Route
          path="/reconciliation"
          element={
            <RequireOps>
              <Reconciliation />
            </RequireOps>
          }
        />
        <Route
          path="/lab"
          element={
            <RequireOps>
              <Lab />
            </RequireOps>
          }
        />
        <Route
          path="/unresolved"
          element={
            <RequireOps>
              <Unresolved />
            </RequireOps>
          }
        />
      </Routes>
    </>
  );
}
