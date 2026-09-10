/**
 * Lab 시험 (FE-M6).
 *
 * 완료 기준: 장애를 주입해 UNKNOWN을 만들고 복구되는 동안 **불변조건 카드가 계속 정상**입니다.
 * 여기서는 그 카드가 서버 값을 정직하게 옮기는지를 봅니다 — 확인하지 못한 것을 정상이라고
 * 말하지 않는 것이 핵심입니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Lab } from "./Lab";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

type Value = { name: string; description: string; value: number | null };
let snapshot: { refreshedAt: string | null; ageSeconds: number | null; refreshDurationMillis: number; values: Value[] } = {
  refreshedAt: "2026-09-10T01:00:00Z",
  ageSeconds: 5,
  refreshDurationMillis: 12,
  values: [
    { name: "paritypay.invariant.unbalanced_ledger_transactions", description: "INV-001 위반", value: 0 },
    { name: "paritypay.invariant.negative_wallet_balances", description: "INV-003 위반", value: 0 },
  ],
};
const pgCalls: unknown[] = [];

const server = setupServer(
  http.get("*/api/v1/admin/invariants", () => HttpResponse.json(snapshot)),
  http.post("*/api/v1/admin/mock-pg/mode", async ({ request }) => {
    pgCalls.push(await request.json());
    return new HttpResponse(null, { status: 204 });
  }),
  http.post("*/api/v1/admin/mock-bank/mode", () => new HttpResponse(null, { status: 204 })),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => {
  server.resetHandlers();
  pgCalls.length = 0;
});
afterAll(() => server.close());

function renderLab() {
  tokenStore.write({
    accessToken: "a",
    expiresIn: 900,
    memberId: "m-1",
    roles: ["OPS_OPERATOR"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Lab />
    </QueryClientProvider>,
  );
}

describe("장애 시뮬레이터", () => {
  it("시나리오마다 무엇이 일어날지 미리 설명합니다", async () => {
    const user = userEvent.setup();
    renderLab();
    await user.selectOptions(screen.getByTestId("scenario"), "bank-timeout-after");
    // 버튼만 있으면 결과를 해석할 수 없습니다.
    expect(screen.getByTestId("explains")).toHaveTextContent("응답만 유실");
  });

  it("웹훅 중복 시나리오는 기관에게 webhookMode를 보냅니다", async () => {
    const user = userEvent.setup();
    renderLab();
    await user.selectOptions(screen.getByTestId("scenario"), "webhook-duplicate");
    await user.click(screen.getByTestId("apply"));

    await screen.findByTestId("applied");
    // 우리가 같은 요청을 두 번 보내는 것이 아니라 기관이 두 번 보내게 만듭니다.
    expect(pgCalls).toEqual([{ webhookMode: "DUPLICATE" }]);
  });

  it("불변조건이 지켜지면 정상이라고 표시합니다", async () => {
    renderLab();
    const cards = await screen.findAllByTestId("invariant-state");
    expect(cards).toHaveLength(2);
    cards.forEach((card) => expect(card).toHaveTextContent("정상"));
  });

  it("위반이 있으면 즉시 대응이라고 말합니다", async () => {
    snapshot = { ...snapshot, values: [{ ...snapshot.values[0]!, value: 2 }] };
    renderLab();
    expect(await screen.findByTestId("invariant-state")).toHaveTextContent("위반 2건");
    snapshot = { ...snapshot, values: [{ ...snapshot.values[0]!, value: 0 }] };
  });

  it("확인하지 못한 값을 정상이라고 말하지 않습니다", async () => {
    // 캐시가 아직 비었는데 '정상'이라고 하면 감시가 거짓말을 합니다.
    snapshot = { refreshedAt: null, ageSeconds: null, refreshDurationMillis: 0, values: [
      { name: "paritypay.invariant.unbalanced_ledger_transactions", description: "INV-001 위반", value: null },
    ] };
    renderLab();
    expect(await screen.findByTestId("invariant-state")).toHaveTextContent("확인하지 못함");
    expect(screen.getByTestId("freshness")).toHaveTextContent("아직 한 번도 계산하지 않았습니다");
  });
});
