/**
 * 주문 내역 (DOC-15 §2).
 *
 * **주문 상태와 결제 상태를 같은 줄에 나란히** 보여 줍니다. 둘이 다를 수 있다는 것이 이 화면의
 * 요점이고, 실제로 둘은 서로 다른 시스템이 가지고 있습니다.
 */
import { Link } from "react-router-dom";
import { ORDER_STATUS_LABEL, listOrders, paymentStatusLabel, type OrderStatus } from "../shop/orders";
import { formatWon } from "../format";
import { usePayments } from "../usePayments";
import { OrderLookup } from "./OrderLookup";

const ORDER_BADGE: Record<OrderStatus, string> = {
  PLACED: "badge--brand",
  SHIPPING: "badge--brand",
  PARTIALLY_RETURNED: "badge--warn",
  CONFIRMED: "badge--ok",
  CANCELED: "",
};

/** 결제 상태 배지입니다. 미확정은 보라이지 빨강이 아닙니다(FE-002). */
export function PaymentBadge({ status }: { status: string | undefined }) {
  const tone =
    status === "APPROVED"
      ? "badge--ok"
      : status === "FAILED"
        ? "badge--danger"
        : status === "PROCESSING" || status === "UNKNOWN"
          ? "badge--unknown"
          : status === "PARTIALLY_CANCELED" || status === "CANCELED"
            ? "badge--warn"
            : "";
  return <span className={`badge ${tone}`}>{paymentStatusLabel(status)}</span>;
}

export function Orders() {
  const orders = listOrders();
  const payments = usePayments(orders.map((o) => o.paymentId));

  return (
    <section className="page">
      <h1>주문 내역</h1>
      {orders.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>주문</th>
                <th className="num">금액</th>
                <th>주문 상태</th>
                <th>결제 상태</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr key={order.orderId} data-testid={`order-${order.orderId}`}>
                  <td>
                    <Link to={`/orders/${order.orderId}`}>{order.productName}</Link>
                  </td>
                  <td className="num money">{formatWon(order.amount)}</td>
                  <td data-testid="order-status">
                    <span className={`badge ${ORDER_BADGE[order.status]}`}>{ORDER_STATUS_LABEL[order.status]}</span>
                  </td>
                  <td data-testid="payment-status">
                    <PaymentBadge
                      status={order.paymentId === undefined ? undefined : payments[order.paymentId]?.status}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {orders.length === 0 && <p className="empty card">주문이 없습니다.</p>}
      <div className="card">
        <OrderLookup />
      </div>
    </section>
  );
}
