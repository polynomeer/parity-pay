/**
 * 인증 API를 생성된 타입 위에 얇게 감쌉니다.
 *
 * 응답 타입은 손으로 쓰지 않고 `generated/customer.ts`에서 가져옵니다. 백엔드가 필드를 바꾸면
 * 여기가 먼저 깨집니다. 근거: docs/14-frontend-design.md §8
 */
import { ApiClient } from "./client.js";
import type { components } from "./generated/customer.js";
import { createTokenManager, type TokenManager, type TokenStore, type Tokens } from "./tokens.js";

type Schemas = components["schemas"];
export type LoginRequest = Schemas["LoginRequest"];
export type RegisterMemberRequest = Schemas["RegisterMemberRequest"];
export type RegisterMemberResponse = Schemas["RegisterMemberResponse"];
export type WalletBalanceResponse = Schemas["WalletBalanceResponse"];
type TokenResponse = Schemas["TokenResponse"];

/** 서버 응답을 클라이언트가 보관하는 형태로 옮깁니다. */
function toTokens(response: TokenResponse): Tokens {
  return {
    accessToken: response.accessToken ?? "",
    refreshToken: response.refreshToken ?? "",
    expiresIn: response.expiresIn ?? 0,
    memberId: response.memberId ?? "",
    roles: response.roles ?? [],
  };
}

/**
 * 토큰 관리자를 만듭니다.
 *
 * 재발급 호출을 여기서 주입하므로, 관리자는 단일 비행만 책임지고 HTTP는 모릅니다.
 */
export function createAuth(baseUrl: string, store: TokenStore): TokenManager {
  const bare = new ApiClient({ baseUrl });
  return createTokenManager(store, async (refreshToken) => {
    const response = await bare.publicWrite<TokenResponse>("/api/v1/auth/tokens/refresh", {
      refreshToken,
    });
    return toTokens(response.data);
  });
}

export async function login(
  client: ApiClient,
  tokens: TokenManager,
  request: LoginRequest,
): Promise<Tokens> {
  const response = await client.publicWrite<TokenResponse>("/api/v1/auth/tokens", request);
  const next = toTokens(response.data);
  tokens.set(next);
  return next;
}

export async function register(
  client: ApiClient,
  request: RegisterMemberRequest,
): Promise<RegisterMemberResponse> {
  const response = await client.publicWrite<RegisterMemberResponse>("/api/v1/members", request);
  return response.data;
}

/**
 * 호출자 자신의 잔액입니다. 스냅샷이므로 `asOf`를 함께 씁니다 (FE-005).
 *
 * `walletId`를 인자로 받지 않는 것이 요점입니다. 클라이언트가 지갑 ID를 보관하면 기기를 바꿨을 때
 * 자기 지갑을 찾지 못합니다 — 가입 응답에만 있고 토큰에는 없기 때문입니다.
 */
export async function getMyWallet(client: ApiClient): Promise<WalletBalanceResponse> {
  const response = await client.get<WalletBalanceResponse>("/api/v1/wallets/me");
  return response.data;
}
