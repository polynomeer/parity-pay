/**
 * 토큰 보관과 단일 비행(single-flight) 재발급 (FE-008).
 *
 * 리프레시 토큰은 **교환할 때마다 회전**합니다(Phase 7). 이전 토큰은 그 즉시 무효가 되므로,
 * 두 요청이 동시에 401을 만나 각자 재발급을 시도하면 **뒤늦은 쪽이 이미 무효가 된 토큰으로
 * 시도해 로그아웃됩니다.**
 *
 * 그래서 재발급은 앱 전체에서 한 번에 하나만 진행하고, 나머지는 그 결과를 기다립니다.
 * 회전이 쿠키 안에서 일어나도 이 문제는 그대로입니다 — 브라우저가 들고 있는 쿠키는 하나뿐이고,
 * 동시에 두 번 교환하면 늦은 쪽이 이미 회전된 값을 보냅니다.
 *
 * 근거: docs/14-frontend-design.md §3 FE-008, ADR-010
 */

/**
 * 클라이언트가 보관하는 세션입니다.
 *
 * **리프레시 토큰은 여기 없습니다.** `HttpOnly` 쿠키에 있어 자바스크립트가 읽을 수 없고, 그것이
 * ADR-010의 요점입니다. 이 인터페이스에 다시 넣으면 그 결정을 되돌리는 것입니다.
 */
export interface Tokens {
  readonly accessToken: string;
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
 * 여기 남는 것은 **액세스 토큰뿐**입니다(15분). 스크립트가 읽어 가도 15분짜리이고, 계속 갱신할
 * 수단인 리프레시 토큰은 `HttpOnly` 쿠키에 있어 읽히지 않습니다. 근거: ADR-010
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

/**
 * 실제 재발급 호출입니다. 순환 의존을 피하려고 주입받습니다.
 *
 * **인자가 없습니다.** 리프레시 토큰은 브라우저가 쿠키로 붙이며 이 코드는 값을 보지 못합니다.
 * 여기에 인자가 다시 생기면 값이 자바스크립트로 돌아왔다는 뜻입니다(ADR-010).
 */
export type RefreshCall = () => Promise<Tokens>;

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
      // 보관된 세션이 없으면 로그아웃 상태입니다. 쿠키가 살아 있을 수는 있지만, 그때는
      // 다시 로그인하는 것이 맞습니다 — 어느 회원의 세션인지 알 수 없기 때문입니다.
      if (store.read() === null) {
        return Promise.reject(new Error("no session"));
      }
      inFlight = call()
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
