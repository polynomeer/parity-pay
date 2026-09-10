/**
 * OPS-01 시험 — 하나의 입력으로 사고 하나를 끝까지 추적할 수 있는지 봅니다.
 *
 * 근거: docs/16-ui-implementation-plan.md §4 FE-M4
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Explorer } from "./Explorer";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const WALLET = "11111111-1111-1111-1111-111111111111";
const PAYMENT = "22222222-2222-2222-2222-222222222222";

type ResolveBody = { query: string; kind: string; references: { referenceId: string; kind: string; summary: string | null }[] };
let resolveResponse: ResolveBody = { query: "", kind: "UNKNOWN", references: [] };

const server = setupServer(
  http.get("*/api/v1/admin/transactions/resolve", () => HttpResponse.json(resolveResponse)),
  http.get("*/api/v1/admin/transactions/:ref/timeline", ({ params }) =>
    HttpResponse.json({
      referenceId: params["ref"],
      entries: [
        {
          kind: "PAYMENT",
          id: PAYMENT,
          status: "APPROVED",
          amount: 30000,
          currency: "KRW",
          occurredAt: "2026-09-10T01:00:00Z",
          detail: "order=ord-1",
        },
        {
          kind: "LEDGER",
          id: "lt-1",
          status: "POSTED",
          amount: 30000,
          currency: "KRW",
          occurredAt: "2026-09-10T01:00:01Z",
          detail: "PAYMENT ref=" + PAYMENT,
        },
      ],
    }),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderExplorer() {
  tokenStore.write({
    accessToken: "a",
    expiresIn: 900,
    memberId: "m-1",
    roles: ["OPS_VIEWER"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Explorer />
    </QueryClientProvider>,
  );
}

describe("OPS-01 거래 검색", () => {
  it("결제 ID를 넣으면 바로 타임라인을 열 수 있습니다", async () => {
    resolveResponse = {
      query: PAYMENT,
      kind: "PAYMENT",
      references: [{ referenceId: PAYMENT, kind: "PAYMENT", summary: null }],
    };
    const user = userEvent.setup();
    renderExplorer();

    await user.type(screen.getByTestId("query"), PAYMENT);
    await user.click(screen.getByTestId("search"));

    expect(await screen.findByTestId("kind")).toHaveTextContent("결제");
    await user.click(await screen.findByTestId("reference"));

    const rows = await screen.findAllByTestId("timeline-row");
    // 결제와 원장이 한 화면에서 시간순으로 보여야 합니다.
    expect(rows).toHaveLength(2);
  });

  it("지갑 ID는 후보를 여럿 보여 줍니다", async () => {
    // 지갑은 거래 하나가 아닙니다. 하나인 척하지 않는 것이 이 화면의 설계입니다.
    resolveResponse = {
      query: WALLET,
      kind: "WALLET",
      references: [
        { referenceId: PAYMENT, kind: "PAYMENT", summary: "주문 ord-1 · APPROVED" },
        { referenceId: "33333333-3333-3333-3333-333333333333", kind: "TOP_UP", summary: "SUCCEEDED" },
      ],
    };
    const user = userEvent.setup();
    renderExplorer();

    await user.type(screen.getByTestId("query"), WALLET);
    await user.click(screen.getByTestId("search"));

    expect(await screen.findByTestId("kind")).toHaveTextContent("지갑");
    expect(await screen.findAllByTestId("reference")).toHaveLength(2);
    // 고르기 전에는 타임라인을 열지 않습니다.
    expect(screen.queryByTestId("timeline")).toBeNull();
  });

  it("못 찾으면 못 찾았다고 말합니다", async () => {
    resolveResponse = { query: "없는값", kind: "UNKNOWN", references: [] };
    const user = userEvent.setup();
    renderExplorer();

    await user.type(screen.getByTestId("query"), "없는값");
    await user.click(screen.getByTestId("search"));

    expect(await screen.findByTestId("not-found")).toBeInTheDocument();
  });
});
