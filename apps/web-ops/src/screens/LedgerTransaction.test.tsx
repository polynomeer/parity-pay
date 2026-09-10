/**
 * 원장 탐색기 시험 (FE-M5).
 *
 * 완료 기준: 결제 한 건에서 원장까지 클릭으로 도달하고, 차변 합계 = 대변 합계가 화면에 보입니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { LedgerTransaction } from "./LedgerTransaction";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const TX = "44444444-4444-4444-4444-444444444444";
let balanced = true;

const server = setupServer(
  http.get("*/api/v1/admin/ledger/transactions/:id", () =>
    HttpResponse.json({
      transactionId: TX,
      referenceType: "PAYMENT",
      referenceId: "55555555-5555-5555-5555-555555555555",
      transactionType: "PAYMENT_APPROVED",
      currency: "KRW",
      status: "POSTED",
      reversalOfTransactionId: null,
      effectiveAt: "2026-09-10T01:00:00Z",
      entries: [
        { entryId: "e-1", accountCode: "USER_PAY_MONEY", ownerId: null, direction: "DEBIT", amount: 30000 },
        { entryId: "e-2", accountCode: "MERCHANT_PAYABLE", ownerId: null, direction: "CREDIT", amount: 30000 },
      ],
      debitTotal: 30000,
      creditTotal: balanced ? 30000 : 20000,
      balanced,
    }),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => {
  server.resetHandlers();
  balanced = true;
});
afterAll(() => server.close());

function renderLedger() {
  tokenStore.write({
    accessToken: "a",
    refreshToken: "r",
    expiresIn: 900,
    memberId: "m-1",
    roles: ["OPS_VIEWER"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <LedgerTransaction transactionId={TX} />
    </QueryClientProvider>,
  );
}

describe("원장 탐색기", () => {
  it("차변과 대변을 계정 코드와 함께 보여 줍니다", async () => {
    renderLedger();
    const rows = await screen.findAllByTestId("entry");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("USER_PAY_MONEY");
    expect(await screen.findByTestId("debit-total")).toHaveTextContent("30,000원");
    expect(screen.getByTestId("credit-total")).toHaveTextContent("30,000원");
  });

  it("균형 여부는 서버가 준 값을 씁니다", async () => {
    renderLedger();
    expect(await screen.findByTestId("balanced")).toHaveTextContent("차변 = 대변");
  });

  it("어긋나면 즉시 확인이 필요하다고 말합니다", async () => {
    // 화면이 스스로 더해서 '맞다'고 하면 안 됩니다. 서버가 아니라고 하면 아닌 것입니다.
    balanced = false;
    renderLedger();
    expect(await screen.findByTestId("balanced")).toHaveTextContent("즉시 확인 필요");
  });

  it("원장을 고치는 버튼이 없습니다", async () => {
    renderLedger();
    await screen.findByTestId("ledger");
    // 확정 원장은 수정하지 않습니다(INV-006). 화면에 그런 버튼이 생기면 API와 어긋납니다.
    expect(screen.queryByRole("button")).toBeNull();
  });
});
