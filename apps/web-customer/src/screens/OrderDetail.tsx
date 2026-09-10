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
    <section>
      <h1>{order.productName}</h1>
      <p>주문번호 {order.orderId}</p>
      <p>{order.merchantName}</p>
      {payment.data !== undefined && (
        <>
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
    fetchStatus: async () => (await getPayment(api, payment.paymentId!)) as never,
    isTerminal: () => true,
  });

  if (cancellable === 0) {
    return <p data-testid="not-cancellable">취소할 수 있는 금액이 없습니다.</p>;
  }

  if (state.kind === "settled") {
    onCanceled(state.value);
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void run();
      }}
    >
      <h2>취소</h2>
      {/* DOC-15 §1.6이 요구한 계산 과정입니다. */}
      <dl>
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
    <div>
      <button type="button" data-testid="confirm" onClick={() => confirm.mutate()}>
        구매확정
      </button>
      {confirm.isSuccess && <p data-testid="confirmed">구매확정되었습니다.</p>}
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
