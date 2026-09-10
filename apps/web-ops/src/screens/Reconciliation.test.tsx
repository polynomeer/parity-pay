/**
 * 대사·보정 시험 (FE-M7 · FE-010~012).
 *
 * 완료 기준: 자기 요청을 자기가 승인할 수 없습니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { Reconciliation } from "./Reconciliation";
import { createQueryClient } from "../queryClient";
import { tokenStore } from "../api";

const OPERATOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const MISMATCH = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const adjustments: { approver: string | null }[] = [];

const server = setupServer(
  http.get("*/api/v1/admin/reconciliation/mismatches", () =>
    HttpResponse.json([
      {
        mismatchId: MISMATCH,
        type: "AMOUNT_MISMATCH",
        referenceType: "PAYMENT",
        referenceId: "ref-1",
        internalAmount: 30000,
        externalAmount: 20000,
        amountDifference: 10000,
        resolutionStatus: "OPEN",
      },
    ]),
  ),
  http.post("*/api/v1/admin/reconciliation/mismatches/:id/adjustments", ({ request }) => {
    adjustments.push({ approver: request.headers.get("X-Approver-Id") });
    return HttpResponse.json({ mismatchId: MISMATCH, resolutionStatus: "RESOLVED" });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => {
  server.resetHandlers();
  adjustments.length = 0;
});
afterAll(() => server.close());

function renderWorkbench() {
  tokenStore.write({
    accessToken: "a",
    refreshToken: "r",
    expiresIn: 900,
    memberId: OPERATOR,
    roles: ["OPS_OPERATOR"],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <Reconciliation />
    </QueryClientProvider>,
  );
}

describe("대사 워크벤치", () => {
  it("불일치를 유형과 차이 금액으로 보여 줍니다", async () => {
    renderWorkbench();
    const row = await screen.findByTestId("mismatch-row");
    expect(row).toHaveTextContent("금액 불일치");
    expect(row).toHaveTextContent("10,000원");
  });

  it("자기 요청을 자기가 승인할 수 없습니다 (FE-010)", async () => {
    const user = userEvent.setup();
    renderWorkbench();
    await user.click(await screen.findByTestId("open-adjust"));

    await user.type(screen.getByTestId("approver"), OPERATOR);

    // 서버도 거부하지만, 누르기 전에 알려 줘야 합니다.
    expect(await screen.findByTestId("self-approval")).toBeInTheDocument();
    expect(screen.getByTestId("submit-adjust")).toBeDisabled();
    expect(adjustments).toHaveLength(0);
  });

  it("사유가 없으면 보낼 수 없습니다 (FE-011)", async () => {
    const user = userEvent.setup();
    renderWorkbench();
    await user.click(await screen.findByTestId("open-adjust"));

    await user.type(screen.getByTestId("approver"), "cccccccc-cccc-cccc-cccc-cccccccccccc");
    await user.selectOptions(screen.getByTestId("debit"), "MERCHANT_RECEIVABLE");
    await user.selectOptions(screen.getByTestId("credit"), "MERCHANT_PAYABLE");
    await user.clear(screen.getByTestId("amount"));
    await user.type(screen.getByTestId("amount"), "10000");

    // 사유를 비워 둔 상태입니다. 기본값을 넣지 않았으므로 보낼 수 없습니다.
    expect(screen.getByTestId("submit-adjust")).toBeDisabled();
  });

  it("다른 승인자를 지정하면 보낼 수 있습니다", async () => {
    const user = userEvent.setup();
    renderWorkbench();
    await user.click(await screen.findByTestId("open-adjust"));

    await user.type(screen.getByTestId("approver"), "cccccccc-cccc-cccc-cccc-cccccccccccc");
    await user.selectOptions(screen.getByTestId("debit"), "MERCHANT_RECEIVABLE");
    await user.selectOptions(screen.getByTestId("credit"), "MERCHANT_PAYABLE");
    await user.clear(screen.getByTestId("amount"));
    await user.type(screen.getByTestId("amount"), "10000");
    await user.type(screen.getByTestId("reason"), "외부 금액이 맞음");
    await user.click(screen.getByTestId("submit-adjust"));

    await screen.findByTestId("adjusted");
    expect(adjustments).toEqual([{ approver: "cccccccc-cccc-cccc-cccc-cccccccccccc" }]);
  });
});
