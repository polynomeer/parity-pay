/**
 * 주문번호로 결제 찾기 (FR-006).
 *
 * 주문 내역은 이 브라우저의 `localStorage`에 있습니다. 앱을 지웠거나 다른 기기면 비어 있고, 멱등
 * 키도 없어 결과를 알아낼 길이 없었습니다. 판매자가 알려 준 주문번호 하나로 서버에 묻는 것이 그
 * 경우의 유일한 복구 경로입니다.
 *
 * "결과 확인 중"을 실패로 바꾸지 않습니다. 서버가 미확정 시도를 실패한 시도보다 앞세워 답하는 것과
 * 같은 이유입니다(ADR-007).
 */
import { useMutation } from "@tanstack/react-query";
import { ApiError, findPaymentByOrderId, type PaymentResponse } from "@paritypay/api-client";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { formatWon } from "../format";
import { paymentStatusLabel } from "../shop/orders";

export function OrderLookup() {
  const [orderId, setOrderId] = useState("");
  const lookup = useMutation<PaymentResponse, Error, string>({
    mutationFn: (id) => findPaymentByOrderId(api, id),
  });

  const notFound = lookup.error instanceof ApiError && lookup.error.status === 404;

  return (
    <section aria-labelledby="order-lookup-heading">
      <h2 id="order-lookup-heading">주문번호로 결제 찾기</h2>
      <p>이 기기에 주문이 없어도, 판매자가 알려 준 주문번호로 결제 결과를 확인할 수 있습니다.</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (orderId.trim() !== "") {
            lookup.mutate(orderId.trim());
          }
        }}
      >
        <label>
          주문번호 <input value={orderId} onChange={(e) => setOrderId(e.target.value)} aria-label="주문번호" />
        </label>{" "}
        <button type="submit" disabled={lookup.isPending}>
          찾기
        </button>
      </form>
      {lookup.data && (
        <p data-testid="lookup-result">
          <strong>{paymentStatusLabel(lookup.data.status)}</strong> · {formatWon(lookup.data.approvedAmount ?? 0)}
          {" · "}
          <Link to={`/orders/${lookup.data.orderId}`}>자세히</Link>
        </p>
      )}
      {notFound && <p data-testid="lookup-result">이 주문번호로 만든 결제가 없습니다.</p>}
      {lookup.error && !notFound && <p role="alert">조회하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>}
    </section>
  );
}
