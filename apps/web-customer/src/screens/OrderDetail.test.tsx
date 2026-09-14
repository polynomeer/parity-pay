/**
 * 주문 상세의 취소 — 결함 L.
 *
 * 환불 응답이 유실되면 서버는 취소를 202 + UNKNOWN으로 접수합니다. 예전 화면은 결제를 한 번 읽고
 * 바로 끝난 것으로 쳐서, 확정되지 않은 취소를 "취소됨"으로 보여 줬습니다. 지금은 취소 자체를 조회해
 * 종결 상태까지 기다립니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { OrderDetail } from "./OrderDetail";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";
import { findOrder, saveOrder } from "../shop/orders";

const PAYMENT_ID = "pay-1";
const CANCELLATION_ID = "cxl-1";
const cancellationGets: string[] = [];
let cancellationStatus = "UNKNOWN";

const server = setupServer(
  http.get(`*/api/v1/payments/${PAYMENT_ID}`, () =>
    HttpResponse.json({
      paymentId: PAYMENT_ID,
      orderId: "order-1",
      status: "APPROVED",
      requestedAmount: 18_000,
      approvedAmount: 18_000,
      canceledAmount: 0,
      cancellableAmount: 18_000,
      currency: "KRW",
    }),
  ),
  http.post(`*/api/v1/payments/${PAYMENT_ID}/cancellations`, () =>
    HttpResponse.json(
      { cancellationId: CANCELLATION_ID, paymentId: PAYMENT_ID, status: "UNKNOWN", requestedAmount: 18_000,
        completedAmount: 0, paymentCanceledAmount: 0, currency: "KRW" },
      { status: 202, headers: { Location: `/api/v1/payments/${PAYMENT_ID}/cancellations/${CANCELLATION_ID}` } },
    ),
  ),
  http.get(`*/api/v1/payments/${PAYMENT_ID}/cancellations/:id`, ({ params }) => {
    cancellationGets.push(String(params["id"]));
    return HttpResponse.json({
      cancellationId: CANCELLATION_ID, paymentId: PAYMENT_ID, status: cancellationStatus, requestedAmount: 18_000,
      completedAmount: cancellationStatus === "COMPLETED" ? 18_000 : 0,
      paymentCanceledAmount: cancellationStatus === "COMPLETED" ? 18_000 : 0, currency: "KRW",
    });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.clear();
  cancellationGets.length = 0;
  cancellationStatus = "UNKNOWN";
  tokenStore.write({ accessToken: "a", expiresIn: 900, memberId: "m-1", roles: ["CUSTOMER"] });
  saveOrder({
    orderId: "order-1", productId: "p-1", productName: "핸드드립 원두 200g", amount: 18_000, merchantId: "mer-1",
    merchantName: "테스트 로스터리", paymentId: PAYMENT_ID, status: "SHIPPING", placedAt: "2026-09-14T00:00:00Z",
  });
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderDetail() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/orders/order-1"]}>
        <Routes>
          <Route path="/orders/:orderId" element={<OrderDetail walletId="w-1" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("취소 — 결함 L", () => {
  it("202로 접수된 취소는 '취소됨'이 아니라 '확인 중'이고, 취소 자체를 조회한다", async () => {
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByTestId("cancel"));

    expect(await screen.findByRole("status")).toHaveTextContent("취소 결과를 확인하고 있습니다");
    // 결제가 아니라 **취소**를 조회해야 합니다. 결제의 취소액은 확정된 뒤에야 움직입니다.
    await waitFor(() => expect(cancellationGets).toContain(CANCELLATION_ID));
    // 확정 전에는 주문 상태를 바꾸지 않습니다. 예전에는 여기서 이미 PARTIALLY_RETURNED였습니다.
    expect(findOrder("order-1")?.status).toBe("SHIPPING");
  });

  it("취소가 확정되면 주문 상태가 바뀐다", async () => {
    cancellationStatus = "COMPLETED";
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByTestId("cancel"));

    await waitFor(() => expect(findOrder("order-1")?.status).toBe("CANCELED"));
  });
});
