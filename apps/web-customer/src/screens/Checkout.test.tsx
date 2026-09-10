/**
 * SCR-06 시험 — FE-M2의 완료 기준을 그대로 옮긴 것입니다.
 *
 * 1. 결제 버튼을 연속으로 눌러도 **승인이 한 건**
 * 2. 응답을 유실시켜도 화면이 **"실패"라고 말하지 않음**
 *
 * 근거: docs/16-ui-implementation-plan.md §4 FE-M2
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Checkout } from "./Checkout";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const WALLET = "11111111-1111-1111-1111-111111111111";
const order = {
  orderId: "order-1",
  merchantId: "22222222-2222-2222-2222-222222222222",
  itemName: "테스트 상품",
  amount: 30_000,
};

/** 서버가 본 승인 요청입니다. 멱등 키별로 한 번만 처리한 척합니다. */
let approvals: { key: string | null }[] = [];
let approveBehavior: "ok" | "unknown" | "insufficient" = "ok";
const settled = new Map<string, string>();

const server = setupServer(
  http.post("*/api/v1/payments", async ({ request }) => {
    const key = request.headers.get("Idempotency-Key");
    approvals.push({ key });
    if (approveBehavior === "insufficient") {
      return HttpResponse.json({ code: "INSUFFICIENT_BALANCE", traceId: "t-1" }, { status: 409 });
    }
    // 같은 키를 다시 받으면 저장된 결과를 돌려줍니다. 서버가 실제로 하는 일입니다(INV-004).
    const existing = key !== null ? settled.get(key) : undefined;
    const paymentId = existing ?? `pay-${approvals.length}`;
    if (key !== null) {
      settled.set(key, paymentId);
    }
    if (approveBehavior === "unknown") {
      return HttpResponse.json(
        { paymentId, orderId: order.orderId, status: "UNKNOWN" },
        { status: 202, headers: { Location: `/api/v1/payments/${paymentId}` } },
      );
    }
    return HttpResponse.json(
      { paymentId, orderId: order.orderId, status: "APPROVED", approvedAmount: order.amount },
      { status: 201 },
    );
  }),
  http.get("*/api/v1/payments/:id", ({ params }) =>
    // 복구가 아직 확정하지 않은 상태입니다.
    HttpResponse.json({ paymentId: params["id"], orderId: order.orderId, status: "UNKNOWN" }),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => {
  server.resetHandlers();
  approvals = [];
  settled.clear();
  approveBehavior = "ok";
  localStorage.clear();
});
afterAll(() => server.close());

function renderCheckout() {
  tokenStore.write({
    accessToken: "a",
    expiresIn: 900,
    memberId: "m-1",
    roles: ["CUSTOMER"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Checkout order={order} walletId={WALLET} />
    </QueryClientProvider>,
  );
}

describe("SCR-06 결제창", () => {
  it("연속으로 눌러도 요청이 한 번만 나갑니다", async () => {
    const user = userEvent.setup();
    renderCheckout();
    const button = await screen.findByTestId("pay");

    // 사용자가 조급하게 여러 번 누릅니다. 버튼 비활성화가 막습니다.
    await user.click(button);
    await user.click(button).catch(() => undefined);
    await user.click(button).catch(() => undefined);

    expect(await screen.findByTestId("result")).toHaveTextContent("결제 완료");
    // **이것은 UX가 막은 것이지 정확성 보장이 아닙니다.** 진짜 보장은 아래 시험이 봅니다.
    expect(approvals).toHaveLength(1);
  });

  it("응답을 못 받고 다시 보내도 같은 키라 결제는 한 건입니다", async () => {
    // 버튼 비활성화가 막지 못하는 경로입니다. 요청은 서버에 닿았는데 응답만 유실됐고,
    // 클라이언트는 결과를 알아내려고 다시 보내야 합니다. 여기서 키가 달라지면 이중 청구입니다.
    let attempt = 0;
    server.use(
      http.post("*/api/v1/payments", async ({ request }) => {
        const key = request.headers.get("Idempotency-Key");
        approvals.push({ key });
        attempt += 1;
        const existing = key !== null ? settled.get(key) : undefined;
        const paymentId = existing ?? `pay-${attempt}`;
        if (key !== null) {
          settled.set(key, paymentId);
        }
        if (attempt === 1) {
          // 서버는 처리했지만 응답이 사용자에게 닿지 않습니다.
          return HttpResponse.error();
        }
        return HttpResponse.json(
          { paymentId, orderId: order.orderId, status: "APPROVED", approvedAmount: order.amount },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByTestId("pay"));

    expect(await screen.findByTestId("result")).toHaveTextContent("결제 완료");
    expect(approvals).toHaveLength(2);
    // 두 요청이 같은 키였으므로 서버가 만든 결제는 하나입니다.
    expect(new Set(approvals.map((a) => a.key)).size).toBe(1);
    expect(settled.size).toBe(1);
  });

  it("202를 받으면 실패라고 말하지 않습니다", async () => {
    approveBehavior = "unknown";
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByTestId("pay"));

    const result = await screen.findByTestId("result");
    expect(result).toHaveTextContent("확인하고 있습니다");
    expect(document.body.textContent).not.toContain("실패");
    // 다시 결제하도록 유도하는 버튼이 없어야 합니다.
    expect(screen.queryByTestId("pay")).toBeNull();
  });

  it("202 뒤에도 멱등 키를 버리지 않습니다", async () => {
    approveBehavior = "unknown";
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByTestId("pay"));
    await screen.findByTestId("result");

    // 키를 버리면 이 결제를 다시는 찾을 수 없습니다 — orderId 조회 경로가 없기 때문입니다.
    expect(localStorage.getItem(`paritypay.intent.payment:${order.orderId}`)).not.toBeNull();
  });

  it("409는 거절로 보여 주고 키를 버립니다", async () => {
    approveBehavior = "insufficient";
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByTestId("pay"));

    expect(await screen.findByTestId("rejected")).toHaveTextContent("잔액이 부족합니다");
    // 업무 규칙 거절은 종결이므로 다음 시도는 새 의도여야 합니다.
    expect(localStorage.getItem(`paritypay.intent.payment:${order.orderId}`)).toBeNull();
  });
});
