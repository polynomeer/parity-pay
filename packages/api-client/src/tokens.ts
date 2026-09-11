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

/**
 * 토큰은 **메모리에만** 둡니다. 브라우저 저장소 구현은 없습니다.
 *
 * 한때 `localStorage`에 두었습니다. 리프레시 토큰이 쿠키로 옮겨간 뒤(ADR-010)에도 액세스 토큰은
 * 거기 남아 있었고, 15분짜리라 유한한 위험이었지만 스크립트가 읽을 수 있는 자격이 하나 남아 있는
 * 상태였습니다. 이제 새로고침하면 토큰이 사라지고, 앱은 쿠키로 조용히 다시 받습니다
 * ({@link TokenManager.restore}). 저장소 구현을 다시 만들면 그 자격이 돌아옵니다.
 */
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
  /**
   * 앱이 뜰 때 세션을 되살립니다. 메모리에 토큰이 있으면 그것을, 없으면 쿠키로 재발급을 시도합니다.
   *
   * 실패는 예외가 아니라 `null`입니다 — 로그아웃 상태는 오류가 아니기 때문입니다. 로그아웃하면
   * 서버가 쿠키를 지우므로(ADR-010) 그때는 여기서 `null`이 나옵니다.
   */
  restore(): Promise<Tokens | null>;
}

/** 탭 사이에 재발급을 직렬화하는 잠금 이름입니다. 같은 오리진의 탭이 공유합니다. */
const REFRESH_LOCK = "paritypay.refresh";

/**
 * 탭 사이의 잠금입니다.
 *
 * 단일 비행은 **한 탭 안**의 동시 재발급만 막습니다. 토큰이 메모리에 있으므로 탭마다 뜰 때 재발급을
 * 하고, 탭 두 개가 동시에 뜨면 같은 쿠키로 두 번 교환합니다 — 회전 때문에 늦은 쪽이 거절됩니다.
 * Web Locks가 같은 오리진의 탭을 줄 세웁니다. 늦은 탭이 잠금을 받을 때는 브라우저 쿠키 항아리에
 * 이미 회전된 새 쿠키가 있으므로 그것으로 성공합니다. 잠금이 없는 환경(jsdom)에서는 그냥 실행합니다.
 */
function withCrossTabLock<T>(run: () => Promise<T>): Promise<T> {
  const locks = (globalThis.navigator as { locks?: LockManager } | undefined)?.locks;
  if (locks === undefined) {
    return run();
  }
  return locks.request(REFRESH_LOCK, run) as Promise<T>;
}

export function createTokenManager(store: TokenStore, call: RefreshCall): TokenManager {
  // 진행 중인 재발급입니다. null이면 진행 중이 아닙니다.
  let inFlight: Promise<Tokens> | null = null;

  function exchange(): Promise<Tokens> {
    if (inFlight !== null) {
      return inFlight;
    }
    inFlight = withCrossTabLock(call)
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
  }

  return {
    current: () => store.read(),
    set: (tokens) => store.write(tokens),
    clear: () => store.clear(),
    refresh: exchange,
    async restore() {
      const held = store.read();
      if (held !== null) {
        return held;
      }
      try {
        return await exchange();
      } catch {
        return null;
      }
    },
  };
}
