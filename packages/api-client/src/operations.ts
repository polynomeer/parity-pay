/**
 * 운영 API입니다. 타입은 생성물에서 가져옵니다.
 *
 * 근거: docs/16-ui-implementation-plan.md §4 FE-M4
 */
import { ApiClient } from "./client.js";
import type { components } from "./generated/operations.js";

type Schemas = components["schemas"];
export type SearchResult = Schemas["SearchResult"];
export type Timeline = Schemas["Timeline"];
export type UnresolvedTopUpResponse = Schemas["UnresolvedTopUpResponse"];
export type UnresolvedPaymentResponse = Schemas["UnresolvedPaymentResponse"];
export type LedgerTransactionResponse = Schemas["LedgerTransactionResponse"];
export type InvariantSnapshot = Schemas["InvariantSnapshot"];
export type MismatchResponse = Schemas["MismatchResponse"];
export type AdjustmentRequest = Schemas["AdjustmentRequest"];
/** 보정 분개에 쓸 수 있는 계정입니다. 생성된 계약에서 나오므로 없는 계정을 고를 수 없습니다. */
export type AdjustmentAccount = NonNullable<AdjustmentRequest["debitAccount"]>;
export const ADJUSTMENT_ACCOUNTS: readonly AdjustmentAccount[] = [
  "BANK_DEPOSIT",
  "PG_RECEIVABLE",
  "MERCHANT_RECEIVABLE",
  "USER_PAY_MONEY",
  "PAYMENT_HOLDING",
  "MERCHANT_PAYABLE",
  "POINT_LIABILITY",
  "UNIDENTIFIED_DEPOSIT",
  "EQUITY_ADJUSTMENT",
  "PLATFORM_FEE_REVENUE",
  "PROVIDER_FEE_EXPENSE",
  "SETTLEMENT_CLEARING",
];

/** 식별자가 무엇인지 알아내고 타임라인을 열 수 있는 참조를 받습니다. */
export async function resolveIdentifier(client: ApiClient, query: string): Promise<SearchResult> {
  const response = await client.get<SearchResult>(
    `/api/v1/admin/transactions/resolve?query=${encodeURIComponent(query)}`,
  );
  return response.data;
}

export async function getTimeline(client: ApiClient, referenceId: string): Promise<Timeline> {
  const response = await client.get<Timeline>(
    `/api/v1/admin/transactions/${encodeURIComponent(referenceId)}/timeline`,
  );
  return response.data;
}

export async function listUnresolvedTopUps(client: ApiClient): Promise<UnresolvedTopUpResponse[]> {
  return (await client.get<UnresolvedTopUpResponse[]>("/api/v1/admin/top-ups")).data;
}

export async function listUnresolvedPayments(client: ApiClient): Promise<UnresolvedPaymentResponse[]> {
  return (await client.get<UnresolvedPaymentResponse[]>("/api/v1/admin/payments")).data;
}

/**
 * 외부 상태를 다시 조회하게 합니다.
 *
 * **결과를 운영자가 고르는 것이 아닙니다.** 조회를 앞당길 뿐이고 확정은 서버가 합니다.
 * 근거: docs/15-ui-screen-plan.md §4.5
 */
export async function resolveTopUpNow(client: ApiClient, topUpId: string): Promise<void> {
  await client.publicWrite(`/api/v1/admin/top-ups/${topUpId}/resolve`, {});
}

export async function resolvePaymentNow(client: ApiClient, paymentId: string): Promise<void> {
  await client.publicWrite(`/api/v1/admin/payments/${paymentId}/resolve`, {});
}

/**
 * 원장 거래 하나입니다.
 *
 * 항목마다 계정 코드가 함께 오고, 차변·대변 합계와 균형 여부는 **서버가 계산합니다.** 화면이
 * 스스로 더하면 그 계산이 진실과 어긋날 수 있습니다.
 */
export async function getLedgerTransaction(
  client: ApiClient,
  transactionId: string,
): Promise<LedgerTransactionResponse> {
  return (
    await client.get<LedgerTransactionResponse>(`/api/v1/admin/ledger/transactions/${transactionId}`)
  ).data;
}

/**
 * 불변조건 현황입니다. 캐시를 읽을 뿐 다시 계산하지 않습니다.
 *
 * `value`가 null이면 **아직 확인하지 못한 것**입니다. 0으로 읽으면 안 됩니다.
 */
export async function getInvariants(client: ApiClient): Promise<InvariantSnapshot> {
  return (await client.get<InvariantSnapshot>("/api/v1/admin/invariants")).data;
}

/** 장애 주입 시나리오입니다. 기관이 실제로 그렇게 행동하게 만듭니다. */
export interface FailureScenario {
  readonly id: string;
  readonly label: string;
  readonly target: "bank" | "pg";
  readonly bankMode?: string;
  readonly pgMode?: string;
  readonly webhookMode?: string;
  readonly statusQueryAvailable?: boolean;
  readonly explains: string;
}

export async function applyBankMode(client: ApiClient, mode: string): Promise<void> {
  await client.publicWrite("/api/v1/admin/mock-bank/mode", { mode });
}

export async function applyPgMode(
  client: ApiClient,
  body: { mode?: string; statusQueryAvailable?: boolean; webhookMode?: string },
): Promise<void> {
  await client.publicWrite("/api/v1/admin/mock-pg/mode", body);
}

export async function listOpenMismatches(client: ApiClient): Promise<MismatchResponse[]> {
  return (await client.get<MismatchResponse[]>("/api/v1/admin/reconciliation/mismatches")).data;
}

/**
 * 보정 분개로 해결합니다.
 *
 * **승인자는 요청자와 달라야 합니다.** 서버가 거부하지만, 화면도 미리 막습니다 — 서버가 막으니
 * 괜찮다고 두면 운영자는 실패한 뒤에야 알게 됩니다(FE-010).
 *
 * 금액을 직접 고치는 것이 아니라 **새 분개를 만드는 것**입니다. 확정 원장은 수정하지 않습니다.
 */
export async function requestAdjustment(
  client: ApiClient,
  mismatchId: string,
  approverId: string,
  request: AdjustmentRequest,
): Promise<MismatchResponse> {
  const response = await client.writeWithHeaders<MismatchResponse>(
    "POST",
    `/api/v1/admin/reconciliation/mismatches/${mismatchId}/adjustments`,
    request,
    { "X-Approver-Id": approverId },
  );
  return response.data;
}
