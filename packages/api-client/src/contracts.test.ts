/**
 * 클라이언트 계약 시험.
 *
 * 여기 있는 것은 UI 시험이 아니라 **서버의 불변조건이 클라이언트 쪽에서도 성립하는지**를 보는
 * 시험입니다. 백엔드가 INV-004를 지켜도, 재시도마다 새 멱등 키를 만드는 클라이언트 앞에서는
 * 아무 의미가 없습니다.
 *
 * 근거: docs/14-frontend-design.md §9
 */
import { describe, expect, it, vi } from "vitest";
import { createAuth, login } from "./auth.js";
import { ApiClient } from "./client.js";
import { ApiError, UnknownResultError, classify } from "./errors.js";
import { beginIntent, endIntent, memoryIntentStore, pendingIntent } from "./idempotency.js";
import { DEFAULT_POLLING, pollUntilSettled } from "./polling.js";
import { createTokenManager, memoryTokenStore, type Tokens } from "./tokens.js";

// 리프레시 토큰이 여기 없는 것이 ADR-010입니다. 쿠키에 있어 자바스크립트가 볼 수 없습니다.
const tokens: Tokens = {
  accessToken: "access-1",
  expiresIn: 900,
  memberId: "m-1",
  roles: ["CUSTOMER"],
};

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

describe("FE-001 멱등 키는 의도가 끝날 때까지 재사용됩니다", () => {
  it("네트워크 실패 후 재시도가 같은 키를 보냅니다", async () => {
    const store = memoryIntentStore();
    const sent: (string | null)[] = [];
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockImplementationOnce((_url, init) => {
        sent.push(new Headers(init?.headers).get("Idempotency-Key"));
        return Promise.reject(new TypeError("network"));
      })
      .mockImplementationOnce((_url, init) => {
        sent.push(new Headers(init?.headers).get("Idempotency-Key"));
        return Promise.resolve(jsonResponse(201, { paymentId: "p-1" }));
      });
    const client = new ApiClient({ baseUrl: "http://x", fetchImpl });

    // 1차 시도 — 응답을 받지 못합니다.
    const first = beginIntent("payment:order-1", { store });
    await expect(
      client.write("POST", "/api/v1/payments", { amount: 1000 }, first.key),
    ).rejects.toBeInstanceOf(UnknownResultError);

    // 2차 시도 — 화면은 같은 이름으로 의도를 다시 시작합니다.
    const second = beginIntent("payment:order-1", { store });
    await client.write("POST", "/api/v1/payments", { amount: 1000 }, second.key);

    expect(sent[0]).not.toBeNull();
    expect(sent[1]).toBe(sent[0]);
  });

  it("의도는 요청을 보내기 전에 저장됩니다", () => {
    const store = memoryIntentStore();
    const intent = beginIntent("payment:order-2", { store });
    // 아직 어떤 요청도 보내지 않았지만 키는 이미 복구 가능합니다.
    expect(pendingIntent("payment:order-2", { store })?.key).toBe(intent.key);
  });

  it("종결한 뒤에는 새 키가 나옵니다", () => {
    const store = memoryIntentStore();
    const first = beginIntent("payment:order-3", { store });
    endIntent("payment:order-3", { store });
    const second = beginIntent("payment:order-3", { store });
    expect(second.key).not.toBe(first.key);
  });
});

describe("FE-002 202와 5xx는 실패가 아닙니다", () => {
  it("202는 오류가 아니라 조회 위치와 함께 돌아옵니다", async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        jsonResponse(202, { paymentId: "p-2", status: "UNKNOWN" }, { Location: "/api/v1/payments/p-2" }),
      );
    const client = new ApiClient({ baseUrl: "http://x", fetchImpl });
    const store = memoryIntentStore();
    const intent = beginIntent("payment:order-4", { store });

    const response = await client.write<{ status: string }>(
      "POST",
      "/api/v1/payments",
      {},
      intent.key,
    );

    expect(response.status).toBe(202);
    expect(response.location).toBe("/api/v1/payments/p-2");
    // 키를 버리지 않았는지가 핵심입니다. 버리면 결과를 알아낼 방법이 사라집니다.
    expect(pendingIntent("payment:order-4", { store })).not.toBeNull();
  });

  it("5xx는 UnknownResultError이지 ApiError가 아닙니다", async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(500, { code: "INTERNAL_ERROR", traceId: "t-1" }));
    const client = new ApiClient({ baseUrl: "http://x", fetchImpl });
    await expect(client.get("/api/v1/wallets/w-1")).rejects.toBeInstanceOf(UnknownResultError);
  });

  it("409는 확정된 거절이므로 ApiError입니다", async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(409, { code: "INSUFFICIENT_BALANCE", traceId: "t-2" }));
    const client = new ApiClient({ baseUrl: "http://x", fetchImpl });
    const error = await client
      .get("/api/v1/wallets/w-1")
      .catch((e: unknown) => e as ApiError);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("INSUFFICIENT_BALANCE");
    expect((error as ApiError).traceId).toBe("t-2");
  });

  it("분류가 '모름'과 '거절'을 가릅니다", () => {
    expect(classify(202)).toBe("unknown");
    expect(classify(200, "RESULT_PENDING")).toBe("unknown");
    expect(classify(503)).toBe("unknown");
    expect(classify(409, "INSUFFICIENT_BALANCE")).toBe("rejected");
    expect(classify(201)).toBe("settled");
  });
});

describe("FE-008 재발급은 단일 비행입니다", () => {
  it("동시 401 두 건이 재발급을 한 번만 호출합니다", async () => {
    const store = memoryTokenStore(tokens);
    const call = vi.fn(async (): Promise<Tokens> => {
      await new Promise((resolve) => setTimeout(resolve, 10));
      return { ...tokens, accessToken: "access-2" };
    });
    const manager = createTokenManager(store, call);

    const [a, b] = await Promise.all([manager.refresh(), manager.refresh()]);

    // 두 번 호출하면 회전 때문에 뒤늦은 쪽이 무효 토큰을 쓰게 됩니다.
    expect(call).toHaveBeenCalledTimes(1);
    expect(a.accessToken).toBe("access-2");
    expect(b.accessToken).toBe("access-2");
  });

  it("재발급이 끝난 뒤에는 다시 호출할 수 있습니다", async () => {
    const store = memoryTokenStore(tokens);
    const call = vi.fn(async (): Promise<Tokens> => ({ ...tokens, accessToken: "access-2" }));
    const manager = createTokenManager(store, call);

    await manager.refresh();
    await manager.refresh();

    expect(call).toHaveBeenCalledTimes(2);
  });

  it("재발급 실패는 세션을 지웁니다", async () => {
    const store = memoryTokenStore(tokens);
    const manager = createTokenManager(store, () => Promise.reject(new Error("rotated away")));
    await expect(manager.refresh()).rejects.toThrow();
    expect(store.read()).toBeNull();
  });

  it("401을 만나면 재발급 후 같은 멱등 키로 한 번 더 보냅니다", async () => {
    const store = memoryTokenStore(tokens);
    const manager = createTokenManager(store, () =>
      Promise.resolve({ ...tokens, accessToken: "access-2" }),
    );
    const seen: { auth: string | null; key: string | null }[] = [];
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation((_url, init) => {
      const headers = new Headers(init?.headers);
      seen.push({ auth: headers.get("Authorization"), key: headers.get("Idempotency-Key") });
      return Promise.resolve(
        seen.length === 1 ? jsonResponse(401, { code: "INVALID_REQUEST" }) : jsonResponse(201, {}),
      );
    });
    const client = new ApiClient({ baseUrl: "http://x", tokens: manager, fetchImpl });
    const intent = beginIntent("payment:order-5", { store: memoryIntentStore() });

    await client.write("POST", "/api/v1/payments", {}, intent.key);

    expect(seen).toHaveLength(2);
    expect(seen[0]?.auth).toBe("Bearer access-1");
    expect(seen[1]?.auth).toBe("Bearer access-2");
    expect(seen[1]?.key).toBe(seen[0]?.key);
  });
});

describe("FE-003 폴링은 측정값을 따릅니다 (M-011)", () => {
  it("기본값이 측정된 최악(45.4초)을 넉넉히 덮습니다", () => {
    // 30초는 처음에 예시로 적었던 값인데, grace가 30초라 그때는 복구가 시작조차 하지
    // 않습니다. 그 값으로 두면 매번 답이 오기 직전에 포기합니다.
    expect(DEFAULT_POLLING.deadlineMs).toBeGreaterThan(45_400 * 1.5);
    expect(DEFAULT_POLLING.intervalMs).toBeGreaterThanOrEqual(1_000);
  });

  it("종결 상태를 만나면 그 값을 돌려줍니다", async () => {
    const statuses = ["UNKNOWN", "UNKNOWN", "SUCCEEDED"];
    let i = 0;
    const result = await pollUntilSettled(
      () => Promise.resolve(statuses[i++] ?? "SUCCEEDED"),
      (s) => s !== "UNKNOWN",
      { intervalMs: 1, deadlineMs: 1_000 },
    );
    expect(result).toEqual({ state: "settled", value: "SUCCEEDED" });
  });

  it("한계를 넘으면 실패가 아니라 pending입니다", async () => {
    let clock = 0;
    const result = await pollUntilSettled(
      () => Promise.resolve("UNKNOWN"),
      (s) => s !== "UNKNOWN",
      { intervalMs: 10, deadlineMs: 50 },
      () => Promise.resolve(void (clock += 10)),
      () => clock,
    );
    // 던지지 않는 것이 요점입니다. 호출부가 이것을 오류로 다루면 사용자가 다시 결제합니다.
    expect(result).toEqual({ state: "pending" });
  });
});

describe("ADR-010 리프레시 토큰은 자바스크립트가 만지지 않습니다", () => {
  it("로그인이 보관하는 것에는 리프레시 값이 없습니다", async () => {
    const store = memoryTokenStore();
    const manager = createTokenManager(store, () => Promise.reject(new Error("불려선 안 됩니다")));
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      // 서버가 실수로 본문에 담아 보내더라도 클라이언트가 주워 담지 않아야 합니다.
      jsonResponse(200, {
        accessToken: "access-1",
        refreshToken: "새어 나온 값",
        expiresIn: 900,
        memberId: "m-1",
        roles: ["CUSTOMER"],
      }),
    );
    const client = new ApiClient({ baseUrl: "http://x", tokens: manager, fetchImpl });

    await login(client, manager, { email: "a@example.com", password: "password1234" });

    expect(JSON.stringify(store.read())).not.toContain("새어 나온 값");
  });

  it("재발급은 본문 없이 쿠키를 실어 보냅니다", async () => {
    const store = memoryTokenStore(tokens);
    let sent: RequestInit | undefined;
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation((_url, init) => {
      sent = init;
      return Promise.resolve(jsonResponse(200, { accessToken: "access-2", expiresIn: 900 }));
    });
    // createAuth가 만드는 내부 클라이언트를 시험에서 갈아 끼울 수 없으므로, 같은 조립을
    // 손으로 합니다 — 확인하려는 것은 재발급 호출이 무엇을 보내는가입니다.
    const bare = new ApiClient({ baseUrl: "http://x", fetchImpl });
    const manager = createTokenManager(store, async () => {
      const response = await bare.publicWrite<{ accessToken: string; expiresIn: number }>(
        "/api/v1/auth/tokens/refresh",
        {},
      );
      return { ...tokens, accessToken: response.data.accessToken };
    });

    await manager.refresh();

    // 본문에 토큰이 실리면 값이 자바스크립트로 돌아왔다는 뜻입니다.
    expect(sent?.body).toBe("{}");
    // 쿠키가 실리지 않으면 배포에서 "로그인은 되는데 15분 뒤 로그아웃"이 됩니다.
    expect(sent?.credentials).toBe("include");
  });

  it("createAuth가 만드는 재발급 호출은 인자를 받지 않습니다", () => {
    // 인자가 다시 생기면 어딘가에서 값을 읽어 넘기고 있다는 뜻입니다.
    const manager = createAuth("http://x", memoryTokenStore());
    expect(manager.refresh.length).toBe(0);
  });
});
