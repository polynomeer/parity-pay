/**
 * 금융 쓰기 요청의 상태 기계 (FE-001·FE-002·FE-003).
 *
 * DOC-14 §3의 상태 기계를 그대로 옮긴 것입니다.
 *
 * ```text
 * idle → submitting ─201/200→ settled
 *                   ─202/네트워크/5xx→ confirming ─폴링→ settled
 *                   ─409→ rejected
 * ```
 *
 * 화면이 직접 구현하지 않고 이 훅을 쓰는 이유는, 규칙 셋이 매번 지켜져야 하기 때문입니다.
 *
 * 1. 재시도는 **같은 멱등 키**로 갑니다. 새 키를 만들면 서버는 다른 의도로 보고 두 번 처리합니다.
 * 2. 202·네트워크 오류·5xx는 **실패가 아닙니다.** 확정될 때까지 조회하며, 키를 버리지 않습니다.
 * 3. 폴링 한계를 넘어도 **실패로 바꾸지 않습니다.** 서버 복구 작업이 확정합니다.
 *
 * 근거: docs/14-frontend-design.md §3, reports/11 M-011
 */
import {
  ApiError,
  DEFAULT_POLLING,
  UnknownResultError,
  beginIntent,
  endIntent,
  pollUntilSettled,
  pendingIntent,
  type ApiResponse,
  type IdempotencyKey,
} from "@paritypay/api-client";
import { useCallback, useState } from "react";
import { intentStore } from "./api";

export type WriteState<T> =
  | { readonly kind: "idle" }
  | { readonly kind: "submitting" }
  /** 결과를 모릅니다. **실패가 아닙니다.** */
  | { readonly kind: "confirming" }
  | { readonly kind: "settled"; readonly value: T }
  /** 업무 규칙 거절입니다. 종결이므로 키를 버립니다. */
  | { readonly kind: "rejected"; readonly code: string; readonly traceId: string | undefined }
  /** 한계까지 확정되지 않았습니다. 실패가 아니라 "아직 모름"입니다. */
  | { readonly kind: "pending" };

export interface SettlingWriteOptions<T> {
  /** 의도 이름. 같은 의도의 재시도는 같은 이름을 씁니다 (예: `payment:order-123`). */
  readonly intentName: string;
  /** 멱등 키를 받아 요청을 보냅니다. */
  readonly submit: (key: IdempotencyKey) => Promise<ApiResponse<T>>;
  /** 확정 여부를 조회합니다. 202를 받은 뒤에만 불립니다. */
  readonly fetchStatus: (accepted: T) => Promise<T>;
  readonly isTerminal: (value: T) => boolean;
}

export function useSettlingWrite<T>(options: SettlingWriteOptions<T>) {
  const [state, setState] = useState<WriteState<T>>({ kind: "idle" });

  const run = useCallback(async () => {
    // 진행 중인 의도가 있으면 그 키를 그대로 씁니다. 새로 만들면 이중 청구입니다.
    const intent = beginIntent(options.intentName, { store: intentStore });
    setState({ kind: "submitting" });

    let accepted: T;
    try {
      const response = await options.submit(intent.key);
      if (response.status !== 202) {
        // 확정된 결과입니다. 이제서야 키를 버립니다.
        endIntent(options.intentName, { store: intentStore });
        setState({ kind: "settled", value: response.data });
        return;
      }
      accepted = response.data;
    } catch (error) {
      if (error instanceof ApiError) {
        // 업무 규칙 거절은 종결입니다. 다시 시도하면 새 의도여야 합니다.
        endIntent(options.intentName, { store: intentStore });
        setState({ kind: "rejected", code: error.code, traceId: error.traceId });
        return;
      }
      if (!(error instanceof UnknownResultError)) {
        throw error;
      }
      // 요청이 서버에 닿았는지 모릅니다. 같은 키로 다시 보내 결과를 알아냅니다.
      const retry = await options.submit(intent.key).catch(() => null);
      if (retry === null) {
        setState({ kind: "pending" });
        return;
      }
      if (retry.status !== 202) {
        endIntent(options.intentName, { store: intentStore });
        setState({ kind: "settled", value: retry.data });
        return;
      }
      accepted = retry.data;
    }

    setState({ kind: "confirming" });
    const result = await pollUntilSettled(
      () => options.fetchStatus(accepted),
      options.isTerminal,
      DEFAULT_POLLING,
    );
    if (result.state === "settled") {
      endIntent(options.intentName, { store: intentStore });
      setState({ kind: "settled", value: result.value });
      return;
    }
    // 한계를 넘었습니다. 실패로 바꾸지 않고 키도 유지합니다.
    setState({ kind: "pending" });
  }, [options]);

  /** 앱을 다시 열었을 때 진행 중이던 의도가 있는지 봅니다. */
  const resumable = pendingIntent(options.intentName, { store: intentStore }) !== null;

  return { state, run, resumable };
}
