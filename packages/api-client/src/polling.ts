/**
 * 미확정 결과를 확정할 때까지 조회하는 정책 (FE-002·FE-003).
 *
 * 값은 추측이 아니라 측정에서 나왔습니다(M-011). 확정까지 걸리는 시간은 부하가 아니라 서버 설정이
 * 정하며, 48건에서 편차가 1초 미만이었습니다.
 *
 * - 응답만 유실된 경우(성공 확정): 중앙값 34.9초, p95 35.2초
 * - 외부에 기록이 없는 경우(실패 확정): 중앙값 45.0초, p95 45.4초
 *
 * 복구 작업의 `grace`가 30초여서 **첫 30초 동안은 결과가 바뀔 수 없습니다.** 그래서 촘촘히
 * 두드리지 않습니다. 한계 90초는 관측된 최악(45.4초)의 두 배이며, 넘어도 실패가 아닙니다.
 *
 * 근거: reports/11 M-011, docs/14-frontend-design.md §3 FE-003
 */

export interface PollingPolicy {
  /** 조회 간격(ms). 30초 동안 아무것도 바뀌지 않으므로 촘촘할 이유가 없습니다. */
  readonly intervalMs: number;
  /** 이 시간을 넘으면 폴링을 멈춥니다. **실패로 바꾸지 않습니다.** */
  readonly deadlineMs: number;
}

export const DEFAULT_POLLING: PollingPolicy = { intervalMs: 2_000, deadlineMs: 90_000 };

/** 폴링이 끝난 이유입니다. `pending`은 실패가 아니라 "아직 모른다"입니다. */
export type PollResult<T> = { readonly state: "settled"; readonly value: T } | { readonly state: "pending" };

/**
 * `isTerminal`이 참이 될 때까지 `fetchOnce`를 반복합니다.
 *
 * 한계를 넘으면 `pending`을 돌려줍니다. 던지지 않는 이유는, 호출부가 이것을 오류로 다루지
 * 않게 하기 위해서입니다.
 */
export async function pollUntilSettled<T>(
  fetchOnce: () => Promise<T>,
  isTerminal: (value: T) => boolean,
  policy: PollingPolicy = DEFAULT_POLLING,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  now: () => number = () => Date.now(),
): Promise<PollResult<T>> {
  const startedAt = now();
  for (;;) {
    const value = await fetchOnce();
    if (isTerminal(value)) {
      return { state: "settled", value };
    }
    if (now() - startedAt >= policy.deadlineMs) {
      return { state: "pending" };
    }
    await sleep(policy.intervalMs);
  }
}
