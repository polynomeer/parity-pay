/**
 * 토큰 보관과 단일 비행(single-flight) 재발급 (FE-008).
 *
 * 리프레시 토큰은 **교환할 때마다 회전**합니다(Phase 7). 이전 토큰은 그 즉시 무효가 되므로,
 * 두 요청이 동시에 401을 만나 각자 재발급을 시도하면 **뒤늦은 쪽이 이미 무효가 된 토큰으로
 * 시도해 로그아웃됩니다.**
 *
 * 그래서 재발급은 앱 전체에서 한 번에 하나만 진행하고, 나머지는 그 결과를 기다립니다.
 *
 * 근거: docs/14-frontend-design.md §3 FE-008
 */

export interface Tokens {
  readonly accessToken: string;
  readonly refreshToken: string;
  /** 액세스 토큰 만료까지 남은 초입니다. 서버가 `expiresIn`으로 줍니다. */
  readonly expiresIn: number;
  readonly memberId: string;
  readonly roles: readonly string[];
}

export interface TokenStore {
  read(): Tokens | null;
  write(tokens: Tokens): void;
  clear(): void;
}

const STORAGE_KEY = "paritypay.tokens";

/**
 * 브라우저 저장소 구현입니다.
 *
 * **알려진 한계**: 리프레시 토큰이 `localStorage`에 있으면 XSS에 노출됩니다. 이상적인 형태는
 * `httpOnly` 쿠키지만 지금 서버는 토큰을 응답 **본문**으로 주므로 서버 변경이 필요합니다.
 * 모르는 채로 두지 않기 위해 여기에 적어 둡니다. 근거: docs/14-frontend-design.md §10, §13 열린 질문 1
 */
export function browserTokenStore(storage: Storage = localStorage): TokenStore {
  return {
    read() {
      const raw = storage.getItem(STORAGE_KEY);
      if (raw === null) {
        return null;
      }
      try {
        return JSON.parse(raw) as Tokens;
      } catch {
        storage.removeItem(STORAGE_KEY);
        return null;
      }
    },
    write: (tokens) => storage.setItem(STORAGE_KEY, JSON.stringify(tokens)),
    clear: () => storage.removeItem(STORAGE_KEY),
  };
}

export function memoryTokenStore(initial: Tokens | null = null): TokenStore {
  let current = initial;
  return {
    read: () => current,
    write: (tokens) => void (current = tokens),
    clear: () => void (current = null),
  };
}

/** 실제 재발급 호출입니다. 순환 의존을 피하려고 주입받습니다. */
export type RefreshCall = (refreshToken: string) => Promise<Tokens>;

export interface TokenManager {
  current(): Tokens | null;
  set(tokens: Tokens): void;
  clear(): void;
  /**
   * 토큰을 재발급합니다. 이미 진행 중이면 **새로 호출하지 않고** 진행 중인 것을 기다립니다.
   *
   * 이것이 이 파일의 존재 이유입니다. 회전하는 리프레시 토큰에서는 동시 재발급이 곧 로그아웃입니다.
   */
  refresh(): Promise<Tokens>;
}

export function createTokenManager(store: TokenStore, call: RefreshCall): TokenManager {
  // 진행 중인 재발급입니다. null이면 진행 중이 아닙니다.
  let inFlight: Promise<Tokens> | null = null;

  return {
    current: () => store.read(),
    set: (tokens) => store.write(tokens),
    clear: () => store.clear(),
    refresh() {
      if (inFlight !== null) {
        return inFlight;
      }
      const tokens = store.read();
      if (tokens === null) {
        return Promise.reject(new Error("no refresh token"));
      }
      inFlight = call(tokens.refreshToken)
        .then((next) => {
          store.write(next);
          return next;
        })
        .catch((error: unknown) => {
          // 재발급이 실패하면 세션은 끝입니다. 남은 토큰으로 계속 시도하면 잠금만 부릅니다.
          store.clear();
          throw error;
        })
        .finally(() => {
          inFlight = null;
        });
      return inFlight;
    },
  };
}
