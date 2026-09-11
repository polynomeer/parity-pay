/**
 * 앱이 쓰는 클라이언트 하나를 만듭니다.
 *
 * 화면 코드는 `fetch`를 직접 부르지 않습니다. 부르면 멱등 키 없이 쓰기를 보낼 수 있고, 그러면
 * 서버의 INV-004가 무의미해집니다. 근거: docs/14-frontend-design.md §7
 */
import { ApiClient, memoryTokenStore, createAuth, browserIntentStore } from "@paritypay/api-client";

// API는 **언제나 자기 오리진**입니다. 개발에서는 Vite가, 배포에서는 nginx가 /api를 프록시합니다.
// 다른 오리진을 가리키는 설정을 두지 않습니다 — 그 순간 교차 오리진 호출이 되어 리프레시 쿠키가
// 조용히 빠집니다. 예전에 있던 VITE_API_BASE_URL을 그래서 없앴습니다. 근거: ADR-011
// 상대 경로를 그대로 두면 fetch가 URL을 파싱하지 못하므로 오리진까지 붙여 둡니다.
const BASE_URL: string = globalThis.location?.origin ?? "";

// 토큰은 메모리에만 있습니다. 새로고침하면 사라지고 앱이 쿠키로 다시 받습니다(App의 Session).
// 멱등 키는 반대로 **반드시** 저장소에 있어야 합니다 — 확인 중에 앱을 껐다 켜도 같은 키로
// 결과를 알아내야 하기 때문입니다(FE-001). 둘의 보관 위치가 다른 것은 우연이 아닙니다.
export const tokenStore = memoryTokenStore();
export const intentStore = browserIntentStore();
export const auth = createAuth(BASE_URL, tokenStore);
export const api = new ApiClient({ baseUrl: BASE_URL, tokens: auth });
