/**
 * 주문 상세 — 결제·부분 취소·구매확정 (DOC-15 §1.4·§1.6·§2).
 *
 * 취소에서 두 가지를 지킵니다.
 *
 * - **취소 가능액은 서버가 정합니다.** 화면의 값은 조회 시점의 것이고, 예약→확정 2단계 때문에
 *   제출 전에 다른 요청이 가져갈 수 있습니다. `CANCELLATION_AMOUNT_EXCEEDED`는 버그가 아니라
 *   정상 결과이므로, 오류 화면 대신 최신 값을 다시 보여 줍니다(FE-006).
 * - 계산 과정을 그대로 보여 줍니다. 얼마가 왜 남는지가 사용자에게 보여야 합니다.
 */
import {
  ApiError,
  cancelPayment,
  confirmOrder,
  getCancellation,
  getPayment,
  userMessage,
  type CancellationResponse,
  type PaymentResponse,
} from "@paritypay/api-client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../api";
import { formatWon } from "../format";
import { findOrder, saveOrder } from "../shop/orders";
import { Checkout } from "./Checkout";
import { PaymentBadge } from "./Orders";
import { useSettlingWrite } from "../useSettlingWrite";

export function OrderDetail({ walletId }: { walletId: string }) {
  const { orderId = "" } = useParams();
  const order = findOrder(orderId);
  const queryClient = useQueryClient();

  const payment = useQuery<PaymentResponse>({
    queryKey: ["payment", order?.paymentId],
    queryFn: () => getPayment(api, order!.paymentId!),
    enabled: order?.paymentId !== undefined,
  });

  if (order === undefined) {
    return <p role="alert">주문을 찾을 수 없습니다.</p>;
  }

  // 아직 결제하지 않은 주문입니다.
  if (order.paymentId === undefined) {
    return (
      <Checkout
        order={{
          orderId: order.orderId,
          merchantId: order.merchantId,
          itemName: order.productName,
          amount: order.amount,
        }}
        walletId={walletId}
        onSettled={(settledPayment) => {
          if (settledPayment.paymentId !== undefined) {
            saveOrder({ ...order, paymentId: settledPayment.paymentId, status: "SHIPPING" });
          }
        }}
      />
    );
  }

  return (
    <section className="page">
      <div className="order-head">
        <small>{order.merchantName}</small>
        <h1>{order.productName}</h1>
        <p className="id">주문번호 {order.orderId}</p>
      </div>
      {payment.data !== undefined && (
        <>
          <div className="card">
            <dl>
              <dt>결제 금액</dt>
              <dd>{formatWon(payment.data.approvedAmount ?? 0)}</dd>
              <dt>취소 가능액</dt>
              <dd>{formatWon(payment.data.cancellableAmount ?? 0)}</dd>
              <dt>결제 상태</dt>
              <dd>
                <PaymentBadge status={payment.data.status} />
              </dd>
            </dl>
          </div>
          <CancelPanel
            payment={payment.data}
            onCanceled={(view) => {
              saveOrder({
                ...order,
                status: view.paymentCanceledAmount === order.amount ? "CANCELED" : "PARTIALLY_RETURNED",
              });
              void queryClient.invalidateQueries({ queryKey: ["payment", order.paymentId] });
              void queryClient.invalidateQueries({ queryKey: ["wallet", "me"] });
            }}
          />
          <ConfirmPanel
            paymentId={order.paymentId}
            onConfirmed={() => saveOrder({ ...order, status: "CONFIRMED" })}
          />
        </>
      )}
    </section>
  );
}

function CancelPanel({
  payment,
  onCanceled,
}: {
  payment: PaymentResponse;
  onCanceled: (view: CancellationResponse) => void;
}) {
  const cancellable = payment.cancellableAmount ?? 0;
  const [amount, setAmount] = useState(cancellable);

  const { state, run } = useSettlingWrite<CancellationResponse>({
    // 취소도 의도 하나입니다. 금액이 포함되어야 다른 금액이 다른 의도가 됩니다.
    intentName: `cancel:${payment.paymentId}:${amount}`,
    submit: (key) =>
      cancelPayment(api, payment.paymentId!, { amount, currency: "KRW", reason: "고객 요청" }, key),
    // 환불 응답이 유실되면 202로 접수되고 취소는 UNKNOWN입니다. 예전에는 결제를 한 번 읽고 바로
    // 끝난 것으로 쳤는데(결함 L), 그러면 확정되지 않은 취소가 "취소됨"으로 보였습니다. 취소 자체를
    // 조회해 종결 상태(COMPLETED·FAILED)까지 기다립니다 — 충전·결제와 같은 규칙입니다(FE-002).
    fetchStatus: (accepted) => getCancellation(api, payment.paymentId!, accepted.cancellationId!),
    isTerminal: (view) => view.status === "COMPLETED" || view.status === "FAILED",
  });

  if (cancellable === 0) {
    return (
      <p className="notice notice--muted" data-testid="not-cancellable">
        취소할 수 있는 금액이 없습니다.
      </p>
    );
  }

  if (state.kind === "settled") {
    onCanceled(state.value);
  }

  return (
    <form
      className="card"
      onSubmit={(event) => {
        event.preventDefault();
        void run();
      }}
    >
      <h2>취소</h2>
      {/* DOC-15 §1.6이 요구한 계산 과정입니다. */}
      <dl className="dl--total">
        <dt>최초 결제액</dt>
        <dd>{formatWon(payment.approvedAmount ?? 0)}</dd>
        <dt>기존 취소액</dt>
        <dd>{formatWon(payment.canceledAmount ?? 0)}</dd>
        <dt>이번 취소액</dt>
        <dd data-testid="cancel-amount">{formatWon(amount)}</dd>
        <dt>취소 후 결제액</dt>
        <dd data-testid="remaining">
          {formatWon((payment.approvedAmount ?? 0) - (payment.canceledAmount ?? 0) - amount)}
        </dd>
      </dl>
      <div className="field-row">
        <input
          type="number"
          min={1}
          max={cancellable}
          value={amount}
          onChange={(e) => setAmount(Number(e.target.value))}
          aria-label="취소 금액"
        />
        <button type="submit" data-testid="cancel" disabled={state.kind === "submitting"}>
          취소하기
        </button>
      </div>
      {state.kind === "rejected" && (
        <p role="alert" data-testid="cancel-rejected">
          {userMessage(state.code)}
        </p>
      )}
      {(state.kind === "confirming" || state.kind === "pending") && (
        <p role="status">취소 결과를 확인하고 있습니다.</p>
      )}
    </form>
  );
}

function ConfirmPanel({ paymentId, onConfirmed }: { paymentId: string; onConfirmed: () => void }) {
  const confirm = useMutation({
    mutationFn: () => confirmOrder(api, paymentId),
    onSuccess: onConfirmed,
  });

  return (
    <div className="card stack">
      <h2>구매확정</h2>
      <p className="muted">확정하면 이 주문은 정산 대상이 됩니다.</p>
      <div>
        <button type="button" className="btn--primary" data-testid="confirm" onClick={() => confirm.mutate()}>
          구매확정
        </button>
      </div>
      {confirm.isSuccess && (
        <p className="notice notice--ok" data-testid="confirmed">
          구매확정되었습니다.
        </p>
      )}
      {confirm.isError && (
        <p role="alert">
          {confirm.error instanceof ApiError
            ? userMessage(confirm.error.code)
            : "구매확정하지 못했습니다."}
        </p>
      )}
    </div>
  );
}
