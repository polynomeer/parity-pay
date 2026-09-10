/**
 * SCR-06 결제창 (FR-005, DOC-15 §1.5).
 *
 * DOC-15가 말한 데모가 여기서 성립합니다 — **결제 버튼을 연속으로 눌러도 승인이 한 번**입니다.
 * 버튼을 잠가서가 아니라, 같은 주문의 재시도가 **같은 멱등 키**로 나가고 서버가 저장된 결과를
 * 돌려주기 때문입니다. 잠금은 UX이고, 정확성은 키가 지킵니다.
 *
 * 근거: docs/15-ui-screen-plan.md §1.5, docs/14-frontend-design.md §3 FE-001~003
 */
import {
  approvePayment,
  getPayment,
  userMessage,
  type PaymentResponse,
} from "@paritypay/api-client";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { formatWon } from "../format";
import { useSettlingWrite } from "../useSettlingWrite";

export interface CheckoutOrder {
  readonly orderId: string;
  readonly merchantId: string;
  readonly itemName: string;
  readonly amount: number;
}

export function Checkout({
  order,
  walletId,
  onSettled,
}: {
  order: CheckoutOrder;
  walletId: string;
  onSettled?: (payment: PaymentResponse) => void;
}) {
  const queryClient = useQueryClient();

  // 의도는 **주문 하나**입니다. 같은 주문의 재시도는 전부 같은 키를 씁니다.
  const { state, run } = useSettlingWrite<PaymentResponse>({
    intentName: `payment:${order.orderId}`,
    submit: (key) =>
      approvePayment(
        api,
        {
          orderId: order.orderId,
          walletId,
          merchantId: order.merchantId,
          amount: order.amount,
          currency: "KRW",
          method: "PAY_MONEY",
        },
        key,
      ),
    fetchStatus: (accepted) => getPayment(api, accepted.paymentId!),
    isTerminal: (view) => view.status !== "UNKNOWN" && view.status !== "PROCESSING",
  });

  if (state.kind === "settled") {
    void queryClient.invalidateQueries({ queryKey: ["wallet", "me"] });
    onSettled?.(state.value);
    return (
      <section>
        <h1 data-testid="result">결제 완료</h1>
        <p>{formatWon(state.value.approvedAmount ?? 0)}</p>
        <p>주문번호 {state.value.orderId}</p>
      </section>
    );
  }

  if (state.kind === "confirming" || state.kind === "pending") {
    return (
      <section>
        <h1 data-testid="result">결제 결과를 확인하고 있습니다</h1>
        <p role="status">
          결제 결과를 확인하고 있습니다. 다시 결제하지 말고 잠시 후 주문 내역에서 상태를 확인해
          주세요.
        </p>
      </section>
    );
  }

  return (
    <section>
      <h1>결제</h1>
      <dl>
        <dt>상품</dt>
        <dd>{order.itemName}</dd>
        <dt>결제 금액</dt>
        <dd data-testid="amount">{formatWon(order.amount)}</dd>
      </dl>
      <button
        type="button"
        data-testid="pay"
        // 연속 클릭을 막지만, 정확성이 여기 걸려 있지는 않습니다.
        disabled={state.kind === "submitting"}
        onClick={() => void run()}
      >
        {state.kind === "submitting" ? "결제 중" : `${formatWon(order.amount)} 결제`}
      </button>
      {state.kind === "rejected" && (
        <p role="alert" data-testid="rejected">
          {userMessage(state.code)}
          {state.traceId !== undefined && <small> (문의번호 {state.traceId})</small>}
        </p>
      )}
    </section>
  );
}
