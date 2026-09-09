/**
 * 멱등 키의 수명 관리 (FE-001).
 *
 * 서버는 `principalId + operation + Idempotency-Key`로 요청을 식별합니다. 키를 만드는 쪽이
 * 클라이언트이므로 **키의 수명을 지키는 책임도 클라이언트에 있습니다.** 재시도할 때마다 새 키를
 * 만들면 서버는 두 요청을 서로 다른 의도로 볼 수밖에 없고, INV-004는 그것을 막지 못합니다.
 *
 * 규칙 세 가지입니다.
 *
 * 1. 키는 **의도 하나**에 하나입니다. "결제하기를 눌렀다"가 의도이고 그 뒤 재시도는 같은 의도입니다.
 * 2. 키는 요청을 보내기 **전에** 저장합니다. 응답을 받고 만들면 응답을 못 받은 경우에 키가 없습니다.
 * 3. 키는 **종결 상태를 확인한 뒤에만** 버립니다. `UNKNOWN`과 네트워크 오류는 종결이 아닙니다.
 *
 * 이 규칙이 특히 중요한 이유: 고객 API에는 `orderId`로 결제를 찾는 경로가 없습니다. 응답을 못 받아
 * `paymentId`를 모르면 **같은 키로 다시 POST하는 것이 결과를 알아낼 유일한 방법**입니다.
 *
 * 근거: docs/14-frontend-design.md §3 FE-001, ADR-007
 */

/**
 * 멱등 키입니다. 브랜드 타입이라 문자열을 그대로 넘길 수 없습니다.
 *
 * 쓰기 요청은 이 타입을 요구하고, 이 타입은 {@link beginIntent}만 만들 수 있습니다. 화면 코드가
 * 즉석에서 `crypto.randomUUID()`를 넘기는 것을 **타입 검사 단계에서** 막기 위해서입니다.
 */
export type IdempotencyKey = string & { readonly __brand: "IdempotencyKey" };

/** 진행 중인 의도 하나입니다. */
export interface Intent {
  /** 이 의도를 식별하는 이름. 화면이 정합니다 (예: `payment:order-123`). */
  readonly name: string;
  readonly key: IdempotencyKey;
  /** 저장 시각. 오래된 의도를 정리할 때 씁니다. */
  readonly startedAt: number;
}

/** 저장소입니다. 브라우저에서는 `localStorage`, 시험에서는 메모리 구현을 넣습니다. */
export interface IntentStore {
  read(name: string): Intent | null;
  write(intent: Intent): void;
  remove(name: string): void;
}

const STORAGE_PREFIX = "paritypay.intent.";

/** 새로고침과 앱 재시작을 견뎌야 하므로 세션 저장소가 아니라 `localStorage`입니다. */
export function browserIntentStore(storage: Storage = localStorage): IntentStore {
  return {
    read(name) {
      const raw = storage.getItem(STORAGE_PREFIX + name);
      if (raw === null) {
        return null;
      }
      try {
        return JSON.parse(raw) as Intent;
      } catch {
        // 손상된 값은 없는 것으로 봅니다. 여기서 던지면 화면이 열리지 않습니다.
        storage.removeItem(STORAGE_PREFIX + name);
        return null;
      }
    },
    write(intent) {
      storage.setItem(STORAGE_PREFIX + intent.name, JSON.stringify(intent));
    },
    remove(name) {
      storage.removeItem(STORAGE_PREFIX + name);
    },
  };
}

/** 시험용 메모리 저장소입니다. */
export function memoryIntentStore(): IntentStore {
  const map = new Map<string, Intent>();
  return {
    read: (name) => map.get(name) ?? null,
    write: (intent) => void map.set(intent.name, intent),
    remove: (name) => void map.delete(name),
  };
}

export interface IdempotencyOptions {
  readonly store: IntentStore;
  /** 시험에서 고정 키를 넣기 위한 자리입니다. */
  readonly newKey?: () => string;
}

/**
 * 의도를 시작하거나, 이미 진행 중인 의도의 키를 그대로 돌려줍니다.
 *
 * **재시도는 이 함수를 다시 부릅니다.** 새 키가 아니라 저장된 키가 나오는 것이 요점입니다.
 */
export function beginIntent(name: string, options: IdempotencyOptions): Intent {
  const existing = options.store.read(name);
  if (existing !== null) {
    return existing;
  }
  const key = (options.newKey ?? (() => crypto.randomUUID()))() as IdempotencyKey;
  const intent: Intent = { name, key, startedAt: Date.now() };
  // 요청을 보내기 전에 저장합니다. 순서가 뒤바뀌면 응답을 못 받은 경우에 키를 잃습니다.
  options.store.write(intent);
  return intent;
}

/**
 * 의도를 끝냅니다. **종결 상태를 확인한 뒤에만** 부릅니다.
 *
 * `UNKNOWN`, 5xx, 네트워크 오류에서 부르면 안 됩니다. 그 상태들은 "모른다"이지 "끝났다"가
 * 아니며, 키를 버리면 결과를 알아낼 방법이 사라집니다.
 */
export function endIntent(name: string, options: IdempotencyOptions): void {
  options.store.remove(name);
}

/** 진행 중인 의도가 있는지 봅니다. 앱을 다시 열었을 때 확인 화면으로 보내는 데 씁니다. */
export function pendingIntent(name: string, options: IdempotencyOptions): Intent | null {
  return options.store.read(name);
}
