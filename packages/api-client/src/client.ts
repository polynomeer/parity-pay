/**
 * HTTP 클라이언트.
 *
 * 설계 규칙 두 가지가 시그니처에 박혀 있습니다.
 *
 * 1. **쓰기는 멱등 키 없이 호출할 수 없습니다.** {@link IdempotencyKey}는 브랜드 타입이고
 *    `beginIntent`만 만들 수 있으므로, 화면에서 즉석 UUID를 넘기면 타입 검사가 막습니다.
 * 2. **재시도는 이 안에서만** 일어나고, 처음 만든 키를 그대로 다시 씁니다. 밖에서 재시도하면
 *    새 키가 생길 수 있습니다.
 *
 * 근거: docs/14-frontend-design.md §3 FE-001·FE-002, §7
 */
import { ApiError, UnknownResultError, classify, type ApiErrorBody } from "./errors.js";
import type { IdempotencyKey } from "./idempotency.js";
import type { TokenManager } from "./tokens.js";

export interface ClientOptions {
  readonly baseUrl: string;
  readonly tokens?: TokenManager;
  /** 시험에서 갈아 끼웁니다. */
  readonly fetchImpl?: typeof fetch;
}

export interface ApiResponse<T> {
  readonly status: number;
  readonly data: T;
  /** 202일 때 서버가 알려 주는 조회 위치입니다. */
  readonly location: string | null;
}

interface RequestOptions {
  readonly method: string;
  readonly path: string;
  readonly body?: unknown;
  readonly idempotencyKey?: IdempotencyKey;
  readonly signal?: AbortSignal;
  /** 401에서 토큰을 재발급하고 한 번 더 시도할지. 재발급 호출 자신은 false입니다. */
  readonly retryOnUnauthorized?: boolean;
  readonly extraHeaders?: Record<string, string>;
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly tokens: TokenManager | undefined;
  private readonly fetchImpl: typeof fetch | undefined;

  constructor(options: ClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.tokens = options.tokens;
    this.fetchImpl = options.fetchImpl;
  }

  /**
   * 호출 시점에 `fetch`를 찾습니다.
   *
   * 생성 시점에 `globalThis.fetch`를 붙잡아 두면, 그 뒤에 `fetch`를 갈아 끼우는 쪽(시험의 MSW,
   * 계측 도구)이 이 클라이언트만 비껴갑니다. 실제로 그렇게 만들었다가 시험이 실제 네트워크로
   * 나가는 것을 보고 고쳤습니다.
   */
  private send(input: string, init: RequestInit): Promise<Response> {
    return (this.fetchImpl ?? globalThis.fetch)(input, init);
  }

  /** 읽기입니다. 부작용이 없으므로 자유롭게 재시도해도 안전합니다. */
  get<T>(path: string, signal?: AbortSignal): Promise<ApiResponse<T>> {
    return this.request<T>({ method: "GET", path, ...(signal ? { signal } : {}) });
  }

  /**
   * 쓰기입니다. **멱등 키가 없으면 호출할 수 없습니다.**
   *
   * 같은 키로 다시 부르면 서버가 저장된 결과를 돌려줍니다. 그것이 네트워크 오류에서
   * 결과를 알아내는 방법입니다.
   */
  write<T>(
    method: "POST" | "PUT" | "PATCH" | "DELETE",
    path: string,
    body: unknown,
    idempotencyKey: IdempotencyKey,
    signal?: AbortSignal,
  ): Promise<ApiResponse<T>> {
    return this.request<T>({ method, path, body, idempotencyKey, ...(signal ? { signal } : {}) });
  }

  /**
   * 추가 헤더가 필요한 쓰기입니다 (예: 보정 분개의 `X-Approver-Id`).
   *
   * 멱등 키를 받지 않습니다 — 이 경로들은 금융 효과를 **새 분개로** 만들고 서버가 중복을
   * 업무 제약으로 막습니다.
   */
  writeWithHeaders<T>(
    method: "POST" | "PUT" | "PATCH" | "DELETE",
    path: string,
    body: unknown,
    headers: Record<string, string>,
  ): Promise<ApiResponse<T>> {
    return this.request<T>({ method, path, body, extraHeaders: headers });
  }

  /** 인증이 필요 없고 멱등 키도 없는 쓰기입니다 (가입·로그인·토큰 재발급). */
  publicWrite<T>(path: string, body: unknown, signal?: AbortSignal): Promise<ApiResponse<T>> {
    return this.request<T>({
      method: "POST",
      path,
      body,
      retryOnUnauthorized: false,
      ...(signal ? { signal } : {}),
    });
  }

  private async request<T>(options: RequestOptions): Promise<ApiResponse<T>> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (options.idempotencyKey !== undefined) {
      headers["Idempotency-Key"] = options.idempotencyKey;
    }
    if (options.extraHeaders !== undefined) {
      Object.assign(headers, options.extraHeaders);
    }
    const tokens = this.tokens?.current();
    if (tokens !== null && tokens !== undefined) {
      headers["Authorization"] = `Bearer ${tokens.accessToken}`;
    }

    let response: Response;
    try {
      response = await this.send(this.baseUrl + options.path, {
        method: options.method,
        headers,
        ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
        ...(options.signal ? { signal: options.signal } : {}),
      });
    } catch (cause) {
      // 요청이 서버에 닿았는지 알 수 없습니다. 쓰기였다면 이미 처리됐을 수 있습니다.
      // 실패로 단정하지 않는 이유입니다. 근거: ADR-007
      throw new UnknownResultError("요청 결과를 확인하지 못했습니다", cause);
    }

    if (
      response.status === 401 &&
      options.retryOnUnauthorized !== false &&
      this.tokens !== undefined
    ) {
      await this.tokens.refresh();
      // 재발급 뒤 한 번만 다시 시도합니다. 같은 멱등 키를 그대로 씁니다.
      return this.request<T>({ ...options, retryOnUnauthorized: false });
    }

    const location = response.headers.get("Location");
    const text = await response.text();
    const parsed: unknown = text.length > 0 ? JSON.parse(text) : null;

    if (response.ok || response.status === 202) {
      return { status: response.status, data: parsed as T, location };
    }

    const body = (parsed ?? { code: "INTERNAL_ERROR" }) as ApiErrorBody;
    if (classify(response.status, body.code) === "unknown") {
      throw new UnknownResultError(body.code, new ApiError(response.status, body));
    }
    throw new ApiError(response.status, body);
  }
}
