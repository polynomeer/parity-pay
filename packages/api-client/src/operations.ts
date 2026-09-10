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
