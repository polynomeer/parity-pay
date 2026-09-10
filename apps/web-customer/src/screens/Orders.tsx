/**
 * 주문 내역 (DOC-15 §2).
 *
 * **주문 상태와 결제 상태를 같은 줄에 나란히** 보여 줍니다. 둘이 다를 수 있다는 것이 이 화면의
 * 요점이고, 실제로 둘은 서로 다른 시스템이 가지고 있습니다.
 */
import { Link } from "react-router-dom";
import { ORDER_STATUS_LABEL, listOrders, paymentStatusLabel } from "../shop/orders";
import { formatWon } from "../format";
import { usePayments } from "../usePayments";

export function Orders() {
  const orders = listOrders();
  const payments = usePayments(orders.map((o) => o.paymentId));

  return (
    <section>
      <h1>주문 내역</h1>
      <table>
        <thead>
          <tr>
            <th>주문</th>
            <th>금액</th>
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
              <td>{formatWon(order.amount)}</td>
              <td data-testid="order-status">{ORDER_STATUS_LABEL[order.status]}</td>
              <td data-testid="payment-status">
                {paymentStatusLabel(
                  order.paymentId === undefined ? undefined : payments[order.paymentId]?.status,
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {orders.length === 0 && <p>주문이 없습니다.</p>}
    </section>
  );
}
