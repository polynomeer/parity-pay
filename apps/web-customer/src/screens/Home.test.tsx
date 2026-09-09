/**
 * SCR-03 시험 — 잔액은 스냅샷이고 기준 시각을 밝힙니다 (FE-005).
 *
 * MSW로 서버를 대역합니다. `/wallets/me`를 쓰므로 화면은 지갑 ID를 알 필요가 없습니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Home } from "./Home";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const server = setupServer(
  http.get("*/api/v1/wallets/me", () =>
    HttpResponse.json({
      walletId: "11111111-1111-1111-1111-111111111111",
      available: 1234500,
      pending: 0,
      currency: "KRW",
      asOf: "2026-09-09T04:05:06Z",
    }),
  ),
);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderHome() {
  tokenStore.write({
    accessToken: "a",
    refreshToken: "r",
    expiresIn: 900,
    memberId: "22222222-2222-2222-2222-222222222222",
    roles: ["CUSTOMER"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Home />
    </QueryClientProvider>,
  );
}

describe("SCR-03 홈", () => {
  it("잔액을 원 단위로 보여 줍니다", async () => {
    renderHome();
    expect(await screen.findByTestId("available")).toHaveTextContent("1,234,500원");
  });

  it("스냅샷 기준 시각을 함께 보여 줍니다", async () => {
    // 기준 시각을 숨기면 사용자는 이 값을 '지금'으로 읽습니다. 잔액은 스냅샷입니다(ADR-008).
    renderHome();
    expect(await screen.findByTestId("as-of")).toHaveTextContent("기준");
  });
});
