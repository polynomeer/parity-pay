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
import { ApiClient } from "./client.js";
import { ApiError, UnknownResultError, classify } from "./errors.js";
import { beginIntent, endIntent, memoryIntentStore, pendingIntent } from "./idempotency.js";
import { createTokenManager, memoryTokenStore, type Tokens } from "./tokens.js";

const tokens: Tokens = {
  accessToken: "access-1",
  refreshToken: "refresh-1",
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
      return { ...tokens, accessToken: "access-2", refreshToken: "refresh-2" };
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
