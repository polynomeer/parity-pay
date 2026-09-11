/**
 * FR-006 시험 — 이 기기에 주문이 없어도 주문번호로 결제 결과를 찾습니다.
 *
 * 보는 것은 두 가지입니다. 미확정을 실패로 바꾸지 않는가, 없는 주문을 오류로 만들지 않는가.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MemoryRouter } from "react-router-dom";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { OrderLookup } from "./OrderLookup";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const server = setupServer(
  http.get("*/api/v1/payments", ({ request }) => {
    const orderId = new URL(request.url).searchParams.get("orderId");
    if (orderId === "order-unknown") {
      return HttpResponse.json({
        paymentId: "pay-1",
        orderId,
        status: "UNKNOWN",
        requestedAmount: 18_000,
        approvedAmount: 0,
        canceledAmount: 0,
        currency: "KRW",
      });
    }
    return HttpResponse.json({ code: "RESOURCE_NOT_FOUND", message: "payment not found" }, { status: 404 });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderLookup() {
  tokenStore.write({ accessToken: "a", expiresIn: 900, memberId: "m-1", roles: ["CUSTOMER"] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter>
        <OrderLookup />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("FR-006 주문번호로 결제 찾기", () => {
  it("미확정 결제는 '결과 확인 중'이지 실패가 아닙니다", async () => {
    renderLookup();
    await userEvent.type(screen.getByLabelText("주문번호"), "order-unknown");
    await userEvent.click(screen.getByRole("button", { name: "찾기" }));

    const result = await screen.findByTestId("lookup-result");
    expect(result).toHaveTextContent("결과 확인 중");
    expect(result).not.toHaveTextContent("실패");
  });

  it("결제가 없는 주문번호는 오류가 아니라 '없음'입니다", async () => {
    renderLookup();
    await userEvent.type(screen.getByLabelText("주문번호"), "order-nope");
    await userEvent.click(screen.getByRole("button", { name: "찾기" }));

    expect(await screen.findByTestId("lookup-result")).toHaveTextContent("결제가 없습니다");
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
