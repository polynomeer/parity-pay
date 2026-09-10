/**
 * SCR-08 시험 — 프로젝션 지연이 화면에서 거짓말을 하지 않는지 봅니다 (FE-004).
 *
 * 근거: docs/16-ui-implementation-plan.md §4 FE-M3
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Transactions } from "./Transactions";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const WALLET = "11111111-1111-1111-1111-111111111111";
let projection: unknown[] = [];

const server = setupServer(
  http.get("*/api/v1/wallets/:id/transactions", () =>
    HttpResponse.json({ transactions: projection, nextCursor: null }),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => {
  server.resetHandlers();
  projection = [];
});
afterAll(() => server.close());

function renderList(optimistic: Parameters<typeof Transactions>[0]["optimistic"] = []) {
  tokenStore.write({
    accessToken: "a",
    refreshToken: "r",
    expiresIn: 900,
    memberId: "m-1",
    roles: ["CUSTOMER"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Transactions walletId={WALLET} optimistic={optimistic} />
    </QueryClientProvider>,
  );
}

const justPaid = {
  referenceId: "pay-1",
  type: "PAYMENT",
  direction: "DEBIT" as const,
  amount: 30_000,
  occurredAt: "2026-09-10T01:00:00Z",
};

describe("SCR-08 거래내역", () => {
  it("프로젝션이 아직 모르는 거래를 '반영 중'으로 먼저 보여 줍니다", async () => {
    // 결제는 성공했지만 소비자가 아직 따라오지 못한 순간입니다.
    projection = [];
    renderList([justPaid]);

    expect(await screen.findByTestId("pending-entry")).toHaveTextContent("상품 결제");
    // 표시 없이 섞으면 사용자는 목록을 진실로 믿습니다.
    expect(screen.getByTestId("pending-badge")).toHaveTextContent("반영 중");
    // "거래내역이 없습니다"라고 말하면 안 됩니다.
    expect(document.body.textContent).not.toContain("거래내역이 없습니다");
  });

  it("프로젝션이 따라오면 낙관적 항목이 사라집니다", async () => {
    projection = [
      {
        transactionId: "t-1",
        type: "PAYMENT",
        direction: "DEBIT",
        amount: 30_000,
        currency: "KRW",
        referenceType: "PAYMENT",
        referenceId: "pay-1",
        occurredAt: "2026-09-10T01:00:00Z",
      },
    ];
    renderList([justPaid]);

    expect(await screen.findByTestId("entry")).toHaveTextContent("상품 결제");
    // 같은 거래가 두 줄로 보이면 안 됩니다.
    expect(screen.queryByTestId("pending-entry")).toBeNull();
  });

  it("정말 비어 있을 때만 비었다고 말합니다", async () => {
    projection = [];
    renderList([]);
    expect(await screen.findByText("거래내역이 없습니다.")).toBeInTheDocument();
  });
});
