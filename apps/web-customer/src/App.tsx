import { Navigate, Route, Routes } from "react-router-dom";
import { Home } from "./screens/Home";
import { Pay } from "./screens/Pay";
import { Login } from "./screens/Login";
import { SignUp } from "./screens/SignUp";
import { tokenStore } from "./api";

function RequireAuth({ children }: { children: React.ReactNode }) {
  return tokenStore.read() === null ? <Navigate to="/login" replace /> : <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<SignUp />} />
      <Route
        path="/pay"
        element={
          <RequireAuth>
            <Pay />
          </RequireAuth>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Home />
          </RequireAuth>
        }
      />
    </Routes>
  );
}
