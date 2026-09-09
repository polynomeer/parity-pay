/**
 * 앱이 쓰는 클라이언트 하나를 만듭니다.
 *
 * 화면 코드는 `fetch`를 직접 부르지 않습니다. 부르면 멱등 키 없이 쓰기를 보낼 수 있고, 그러면
 * 서버의 INV-004가 무의미해집니다. 근거: docs/14-frontend-design.md §7
 */
import { ApiClient, browserTokenStore, createAuth, browserIntentStore } from "@paritypay/api-client";

// 개발에서는 Vite 프록시를 타므로 같은 오리진입니다. 배포에서는 API가 다른 오리진이라
// VITE_API_BASE_URL을 줍니다. 상대 경로를 그대로 두면 fetch가 URL을 파싱하지 못하므로
// 오리진까지 붙여 둡니다.
const BASE_URL: string =
  (import.meta.env["VITE_API_BASE_URL"] as string | undefined) ?? globalThis.location?.origin ?? "";

export const tokenStore = browserTokenStore();
export const intentStore = browserIntentStore();
export const auth = createAuth(BASE_URL, tokenStore);
export const api = new ApiClient({ baseUrl: BASE_URL, tokens: auth });
