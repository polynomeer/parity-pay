import { Navigate, Route, Routes } from "react-router-dom";
import { Home } from "./screens/Home";
import { Pay } from "./screens/Pay";
import { Products } from "./screens/Products";
import { Orders } from "./screens/Orders";
import { OrderDetailRoute } from "./screens/OrderDetailRoute";
import { TransactionsRoute } from "./screens/TransactionsRoute";
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
        path="/shop"
        element={
          <RequireAuth>
            <Products />
          </RequireAuth>
        }
      />
      <Route
        path="/orders"
        element={
          <RequireAuth>
            <Orders />
          </RequireAuth>
        }
      />
      <Route
        path="/orders/:orderId"
        element={
          <RequireAuth>
            <OrderDetailRoute />
          </RequireAuth>
        }
      />
      <Route
        path="/transactions"
        element={
          <RequireAuth>
            <TransactionsRoute />
          </RequireAuth>
        }
      />
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
