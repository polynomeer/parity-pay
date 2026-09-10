/**
 * SCR-05 페이머니 충전 (FR-003, DOC-15 §1.2).
 *
 * 결과를 **셋으로** 구분합니다. 이것이 이 화면의 핵심입니다.
 *
 * - 완료
 * - 실패
 * - **확인 중** — 타임아웃을 실패로 표현하지 않습니다
 *
 * 근거: docs/15-ui-screen-plan.md §1.2, docs/14-frontend-design.md §3 FE-002
 */
import { getTopUp, requestTopUp, userMessage, type TopUpResponse } from "@paritypay/api-client";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { formatWon } from "../format";
import { useSettlingWrite } from "../useSettlingWrite";

const QUICK_AMOUNTS = [10_000, 30_000, 50_000, 100_000];

export function TopUp({ walletId, bankAccountId }: { walletId: string; bankAccountId: string }) {
  const [amount, setAmount] = useState(10_000);
  const queryClient = useQueryClient();

  // 의도 이름에 금액을 넣습니다. 금액을 바꾸면 다른 의도이고, 같은 키를 쓰면 서버가
  // IDEMPOTENCY_KEY_REUSED로 거절합니다(같은 키·다른 본문).
  const { state, run } = useSettlingWrite<TopUpResponse>({
    intentName: `top-up:${walletId}:${amount}`,
    submit: (key) =>
      requestTopUp(api, { walletId, bankAccountId, amount, currency: "KRW" }, key),
    fetchStatus: (accepted) => getTopUp(api, accepted.topUpId!),
    isTerminal: (view) => view.status === "SUCCEEDED" || view.status === "FAILED",
  });

  if (state.kind === "settled") {
    const succeeded = state.value.status === "SUCCEEDED";
    return (
      <section>
        <h1>{succeeded ? "충전 완료" : "충전 실패"}</h1>
        <p>{formatWon(state.value.requestedAmount ?? 0)}</p>
        <button
          type="button"
          onClick={() => queryClient.invalidateQueries({ queryKey: ["wallet", "me"] })}
        >
          잔액 다시 보기
        </button>
      </section>
    );
  }

  if (state.kind === "confirming" || state.kind === "pending") {
    return (
      <section>
        <h1>처리 결과를 확인하고 있습니다</h1>
        {/* 실패라고 말하지 않습니다. 다시 충전하면 이중 출금이 됩니다. */}
        <p role="status">
          금융기관에서 처리 결과를 확인하고 있습니다. 다시 충전하지 말고 잠시 후 거래 상태를 확인해
          주세요.
        </p>
        {/* "다시 시도" 버튼을 두지 않습니다. 근거: DOC-14 FE-002 */}
      </section>
    );
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void run();
      }}
    >
      <h1>충전</h1>
      <div>
        {QUICK_AMOUNTS.map((value) => (
          <button key={value} type="button" onClick={() => setAmount(value)}>
            +{formatWon(value)}
          </button>
        ))}
      </div>
      <label>
        충전 금액
        <input
          type="number"
          min={1}
          step={1}
          value={amount}
          onChange={(e) => setAmount(Number(e.target.value))}
        />
      </label>
      <button type="submit" disabled={state.kind === "submitting"}>
        {state.kind === "submitting" ? "요청 중" : `${formatWon(amount)} 충전`}
      </button>
      {state.kind === "rejected" && (
        <p role="alert">
          {userMessage(state.code)}
          {state.traceId !== undefined && <small> (문의번호 {state.traceId})</small>}
        </p>
      )}
    </form>
  );
}

/** 홈에서 쓰는 "확인 중인 거래" 표시입니다. DOC-15 §1.1의 '처리 중 금액'에 대응합니다. */
export function useTopUpView(topUpId: string | null) {
  return useQuery({
    queryKey: ["top-up", topUpId],
    queryFn: () => getTopUp(api, topUpId!),
    enabled: topUpId !== null,
  });
}
