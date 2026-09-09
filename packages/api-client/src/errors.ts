/**
 * 서버 오류 코드와 화면 처리 규칙 (FE-002·FE-009).
 *
 * 핵심은 하나입니다. **모든 실패가 실패는 아닙니다.** 202와 `RESULT_PENDING`은 "아직 모른다"이고,
 * 이것을 오류로 표시하면 사용자가 다시 결제해서 서버가 막아 둔 이중 청구를 되살립니다.
 *
 * 근거: docs/14-frontend-design.md §3 FE-002·FE-009, ADR-007
 */

/** 서버 `ErrorCode`와 1:1입니다. */
export const ERROR_CODES = [
  "INVALID_AMOUNT",
  "INVALID_REQUEST",
  "WALLET_NOT_ACTIVE",
  "INSUFFICIENT_BALANCE",
  "IDEMPOTENCY_KEY_REUSED",
  "INVALID_STATE_TRANSITION",
  "CANCELLATION_AMOUNT_EXCEEDED",
  "LIMIT_EXCEEDED",
  "RISK_BLOCKED",
  "RESOURCE_NOT_FOUND",
  "RESULT_PENDING",
  "EXTERNAL_TEMPORARY_ERROR",
  "INTERNAL_ERROR",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

export interface ApiErrorBody {
  readonly code: string;
  readonly message?: string;
  readonly traceId?: string;
  readonly details?: Record<string, unknown>;
}

/** 서버가 업무 규칙으로 거절한 요청입니다. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode | string;
  readonly traceId: string | undefined;
  readonly details: Record<string, unknown>;

  constructor(status: number, body: ApiErrorBody) {
    super(body.code);
    this.name = "ApiError";
    this.status = status;
    this.code = body.code;
    this.traceId = body.traceId;
    this.details = body.details ?? {};
  }
}

/**
 * 요청을 보냈는데 **결과를 모르는** 상태입니다. 네트워크 단절, 5xx, `RESULT_PENDING`이 여기 옵니다.
 *
 * 실패가 아니므로 이 오류를 만난 화면은 다음을 지킵니다.
 *
 * - "실패" 문구를 쓰지 않습니다.
 * - 멱등 키를 버리지 않습니다.
 * - 새 요청을 만들지 않고, 같은 키로 재확인하거나 조회로 확정합니다.
 */
export class UnknownResultError extends Error {
  readonly cause: unknown;

  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "UnknownResultError";
    this.cause = cause;
  }
}

/** 화면이 결과를 어떻게 다뤄야 하는지입니다. */
export type Outcome = "settled" | "rejected" | "unknown";

/**
 * 상태 코드와 오류 코드를 화면 처리 방식으로 옮깁니다.
 *
 * `unknown`은 멱등 키를 유지해야 하는 경우이고, `rejected`는 버려도 되는 경우입니다.
 */
export function classify(status: number, code?: string): Outcome {
  if (status === 202 || code === "RESULT_PENDING") {
    return "unknown";
  }
  if (status >= 500 || code === "EXTERNAL_TEMPORARY_ERROR" || code === "INTERNAL_ERROR") {
    return "unknown";
  }
  if (status >= 400) {
    return "rejected";
  }
  return "settled";
}

/**
 * 사용자에게 보일 문구입니다.
 *
 * 서버 원문 메시지는 쓰지 않습니다. 내부 사정을 노출하고, 사용자가 할 수 있는 일을 알려 주지도
 * 않기 때문입니다.
 */
export function userMessage(code: string): string {
  switch (code) {
    case "INSUFFICIENT_BALANCE":
      return "잔액이 부족합니다.";
    case "LIMIT_EXCEEDED":
      return "한도를 넘었습니다.";
    case "CANCELLATION_AMOUNT_EXCEEDED":
      return "취소할 수 있는 금액을 넘었습니다. 최신 금액을 다시 확인해 주세요.";
    case "WALLET_NOT_ACTIVE":
      return "지금은 사용할 수 없는 지갑입니다.";
    case "INVALID_STATE_TRANSITION":
      return "지금 상태에서는 할 수 없는 작업입니다.";
    case "RISK_BLOCKED":
      // 왜 막혔는지 자세히 알리지 않습니다.
      return "요청이 승인되지 않았습니다.";
    case "INVALID_AMOUNT":
    case "INVALID_REQUEST":
      return "입력한 내용을 다시 확인해 주세요.";
    case "RESOURCE_NOT_FOUND":
      return "찾을 수 없습니다.";
    case "IDEMPOTENCY_KEY_REUSED":
      // 이것은 사실상 클라이언트 버그입니다. 같은 키에 다른 본문을 보냈다는 뜻입니다.
      return "이전 요청과 내용이 다릅니다. 처음부터 다시 시도해 주세요.";
    case "EXTERNAL_TEMPORARY_ERROR":
    case "RESULT_PENDING":
      return "처리 결과를 확인하고 있습니다.";
    default:
      return "문제가 생겼습니다. 잠시 후 다시 시도해 주세요.";
  }
}
