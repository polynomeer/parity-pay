import { Navigate, Route, Routes } from "react-router-dom";
import { Home } from "./screens/Home";
import { Pay } from "./screens/Pay";
import { Products } from "./screens/Products";
import { Orders } from "./screens/Orders";
import { OrderDetailRoute } from "./screens/OrderDetailRoute";
import { TransactionsRoute } from "./screens/TransactionsRoute";
import { Settlements } from "./screens/Settlements";
import { Login } from "./screens/Login";
import { SignUp } from "./screens/SignUp";
import { ForgotPassword } from "./screens/ForgotPassword";
import { ResetPassword } from "./screens/ResetPassword";
import { AuthShell, Shell } from "./Shell";
import { tokenStore } from "./api";

function RequireAuth({ children }: { children: React.ReactNode }) {
  return tokenStore.read() === null ? <Navigate to="/login" replace /> : <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route element={<AuthShell />}>
        <Route path="/login" element={<Login />} />
        <Route path="/signup" element={<SignUp />} />
        <Route path="/forgot" element={<ForgotPassword />} />
        <Route path="/reset" element={<ResetPassword />} />
      </Route>
      <Route
        element={
          <RequireAuth>
            <Shell />
          </RequireAuth>
        }
      >
        <Route path="/" element={<Home />} />
        <Route path="/shop" element={<Products />} />
        <Route path="/orders" element={<Orders />} />
        <Route path="/orders/:orderId" element={<OrderDetailRoute />} />
        <Route path="/transactions" element={<TransactionsRoute />} />
        <Route path="/pay" element={<Pay />} />
        <Route path="/settlements" element={<Settlements />} />
      </Route>
    </Routes>
  );
}
