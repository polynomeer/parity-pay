/**
 * 판매자 정산 시험 (FE-M7).
 *
 * 완료 기준: 정산액이 어떤 주문에서 왔는지 화면에서 추적할 수 있습니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Settlements } from "./Settlements";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const SETTLEMENT = "77777777-7777-7777-7777-777777777777";

const server = setupServer(
  http.get("*/api/v1/merchant/settlements", () =>
    HttpResponse.json([
      {
        settlementId: SETTLEMENT,
        merchantId: "88888888-8888-8888-8888-888888888888",
        periodStart: "2026-09-01",
        periodEnd: "2026-09-07",
        grossAmount: 50000,
        cancellationAmount: 0,
        feeAmount: 5000,
        adjustmentAmount: 0,
        netAmount: 45000,
        currency: "KRW",
        status: "CALCULATED",
      },
    ]),
  ),
  http.get("*/api/v1/merchant/settlements/:id/items", () =>
    HttpResponse.json([
      {
        itemId: "i-1",
        paymentId: "99999999-9999-9999-9999-999999999999",
        itemType: "SALE",
        amount: 50000,
        currency: "KRW",
        status: "SETTLED",
        sourceReferenceId: "pay-1",
      },
      {
        itemId: "i-2",
        paymentId: "99999999-9999-9999-9999-999999999999",
        itemType: "FEE",
        amount: -5000,
        currency: "KRW",
        status: "SETTLED",
        sourceReferenceId: "fee-1",
      },
    ]),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderSettlements() {
  tokenStore.write({
    accessToken: "a",
    refreshToken: "r",
    expiresIn: 900,
    memberId: "m-1",
    roles: ["MERCHANT"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Settlements />
    </QueryClientProvider>,
  );
}

describe("판매자 정산", () => {
  it("정산 금액의 근거를 항목으로 보여 줍니다", async () => {
    const user = userEvent.setup();
    renderSettlements();

    await user.click(await screen.findByTestId("open-items"));

    const rows = await screen.findAllByTestId("item-row");
    expect(rows).toHaveLength(2);
    // 어떤 결제에서 왔는지가 보여야 추적이 됩니다.
    expect(rows[0]).toHaveTextContent("99999999");
  });

  it("항목 합계가 정산 순액과 같습니다 (INV-008)", async () => {
    const user = userEvent.setup();
    renderSettlements();
    await user.click(await screen.findByTestId("open-items"));

    const sum = await screen.findByTestId("item-sum");
    // 둘이 다르면 판매자는 어느 쪽도 믿을 수 없습니다.
    expect(sum).toHaveTextContent("45,000원");
    expect(screen.getByTestId("net")).toHaveTextContent("45,000원");
  });

  it("수수료의 음수 부호를 지우지 않습니다", async () => {
    const user = userEvent.setup();
    renderSettlements();
    await user.click(await screen.findByTestId("open-items"));

    const amounts = await screen.findAllByTestId("item-amount");
    // 부호를 지우면 무엇이 빼갔는지 알 수 없습니다.
    expect(amounts[1]).toHaveTextContent("-5,000원");
  });
});
