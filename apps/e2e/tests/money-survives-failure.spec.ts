/**
 * E2E — 장애를 주입한 채 Shop → 결제 → 복구를 한 바퀴 돕니다.
 *
 * **이 시험만 볼 수 있는 것**을 봅니다. 백엔드 시험은 서버 안쪽을, 프론트 시험은 목을 상대로
 * 화면을 봅니다. 둘 다 통과하면서도 **실제 응답이 화면이 기대하는 모양이 아닐 수** 있습니다.
 * 결함 J가 그랬습니다 — 목이 타임라인 응답을 만들어 주는 바람에 원장 줄이 서버에서 오지 않는다는
 * 사실이 43개 프론트 시험에 전혀 보이지 않았습니다.
 *
 * 근거: reports/11 결함 J, docs/14-frontend-design.md §9
 */
import { expect, request, test, type Page } from "@playwright/test";

/**
 * 두 앱은 **다른 호스트이름**에 있습니다. 포트만 다르면 쿠키가 서로 넘어갑니다(ADR-011).
 * `*.localhost`는 브라우저가 /etc/hosts 없이 루프백으로 풀어 주므로 설치할 것이 없습니다.
 */
const OPS_URL = process.env["E2E_OPS_URL"] ?? "http://ops.localhost:5174";
// Node 쪽 호출입니다. 쿠키와 무관하므로 호스트이름을 가를 이유가 없습니다.
const API_URL = process.env["E2E_API_URL"] ?? "http://localhost:8080";
// 배포 스택에서는 운영자 비밀번호가 생성됩니다(ADR-011). 기본값은 local 프로필의 값입니다.
const OPS_EMAIL = process.env["E2E_OPS_EMAIL"] ?? "ops-operator@paritypay.local";
const OPS_PASSWORD = process.env["E2E_OPS_PASSWORD"] ?? "local-ops-password";

/**
 * React가 관찰하는 방식으로 입력값을 채웁니다.
 *
 * Playwright의 `fill()`이면 충분하지만, 폼 제출은 `requestSubmit()`으로 합니다 — 이 앱의 폼은
 * 버튼 클릭이 아니라 submit 이벤트에 매여 있습니다.
 */
async function submitForm(page: Page, values: Record<string, string>) {
  for (const [selector, value] of Object.entries(values)) {
    await page.fill(selector, value);
  }
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
}

/**
 * 가입하고 계좌를 연결해 충전할 수 있는 상태로 만듭니다.
 *
 * 각 단계가 **끝난 것을 확인하고** 다음으로 갑니다. 기다리지 않고 넘어가면 토큰이 아직 없어
 * 보호된 경로가 로그인으로 되돌립니다 — 처음 이 시험을 쓸 때 실제로 그렇게 실패했습니다.
 */
const PASSWORD = "password1234";

/** 브라우저 밖에서 이 회원으로 API를 부를 때 씁니다. 토큰이 메모리에만 있어 화면에서 꺼낼 수 없습니다. */
async function customerToken(email: string): Promise<string> {
  const client = await api();
  try {
    const response = await client.post("/api/v1/auth/tokens", { data: { email, password: PASSWORD } });
    return ((await response.json()) as { accessToken: string }).accessToken;
  } finally {
    await client.dispose();
  }
}

async function signUpAndLinkAccount(page: Page, accountNumber: string): Promise<string> {
  const email = `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
  await page.goto("/signup");
  await submitForm(page, {
    'input[type="email"]': email,
    'input[type="password"]': PASSWORD,
  });
  // 가입이 끝나면 홈으로 넘어갑니다. 여기까지 와야 토큰이 저장되어 있습니다.
  await expect(page.getByRole("heading", { name: "페이머니" })).toBeVisible();

  await page.goto("/pay");
  await submitForm(page, { "form input": accountNumber });
  await expect(page.getByRole("heading", { name: "충전" })).toBeVisible();
  return email;
}

/** 충전 폼을 제출합니다. 화면의 제출 버튼은 빠른 금액 버튼과 문구가 겹칩니다. */
async function submitTopUp(page: Page): Promise<void> {
  await page.evaluate(() => {
    const submit = [...document.querySelectorAll("button")].find(
      (b) => b.getAttribute("type") === "submit" && b.textContent?.includes("충전"),
    );
    submit?.click();
  });
}

/**
 * 브라우저 밖에서 API를 부르는 통로입니다.
 *
 * 브라우저 컨텍스트와 **쿠키를 공유하지 않습니다.** 운영자로 로그인한 쿠키가 고객 브라우저에
 * 들어가면 세션 분리 시험이 자기 손으로 오염됩니다. 배포 스택은 자체 서명 TLS라 검증을 끕니다.
 */
async function api() {
  return request.newContext({ baseURL: API_URL, ignoreHTTPSErrors: true });
}

/** 운영자 토큰을 API로 직접 받습니다. 장애 주입은 화면이 아니라 계약을 확인하는 단계입니다. */
async function operatorToken(): Promise<string> {
  const client = await api();
  try {
    const response = await client.post("/api/v1/auth/tokens", {
      data: { email: OPS_EMAIL, password: OPS_PASSWORD },
    });
    const body = (await response.json()) as { accessToken: string };
    return body.accessToken;
  } finally {
    await client.dispose();
  }
}

async function setBankMode(token: string, mode: string): Promise<void> {
  const client = await api();
  try {
    const response = await client.post("/api/v1/admin/mock-bank/mode", {
      headers: { Authorization: `Bearer ${token}` },
      data: { mode },
    });
    expect(response.status()).toBe(204);
  } finally {
    await client.dispose();
  }
}

/**
 * ADR-010은 **브라우저만 증명할 수 있습니다.**
 *
 * 백엔드 시험은 `Set-Cookie` 문자열을 읽고 손으로 되돌려 보내고, jsdom 시험은 `fetch`를 흉내
 * 냅니다. 둘 다 통과하면서도 실제 브라우저가 쿠키를 저장하지 않거나 돌려보내지 않을 수 있습니다 —
 * `Path`가 어긋나거나 `Secure`가 http에서 걸리면 그렇게 됩니다. 증상은 "로그인은 되는데 15분 뒤
 * 로그아웃"이라 원인을 찾기 어렵습니다.
 */
test.describe("리프레시 토큰은 스크립트가 만지지 못한다 (ADR-010)", () => {
  test("쿠키는 저장되지만 읽히지 않고, 그것만으로 재발급된다", async ({ page, context }) => {
    await signUpAndLinkAccount(page, "110-7777-8888");

    const cookie = (await context.cookies()).find((c) => c.name === "paritypay_refresh");
    expect(cookie, "브라우저가 리프레시 쿠키를 저장하지 않았습니다").toBeDefined();
    expect(cookie!.httpOnly).toBe(true);
    expect(cookie!.sameSite).toBe("Lax");
    expect(cookie!.path).toBe("/api/v1/auth");

    // 이 결정의 전부입니다 — 스크립트가 뚫려도 값이 나가지 않습니다.
    expect(await page.evaluate(() => document.cookie)).not.toContain("paritypay_refresh");
    // 브라우저 저장소에는 토큰이 아예 없습니다. 액세스 토큰도 메모리에만 있습니다.
    expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.includes("token")))).toEqual([]);

    // 본문 없이, 쿠키만으로 재발급됩니다. 브라우저가 알아서 붙입니다.
    const refreshed = await page.evaluate(async () => {
      const response = await fetch("/api/v1/auth/tokens/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
        credentials: "include",
      });
      return { status: response.status, body: (await response.json()) as Record<string, unknown> };
    });

    expect(refreshed.status).toBe(200);
    expect(refreshed.body["accessToken"]).toBeTruthy();
    expect(refreshed.body).not.toHaveProperty("refreshToken");

    // 회전했으므로 브라우저가 들고 있는 쿠키도 새 값이어야 합니다.
    const rotated = (await context.cookies()).find((c) => c.name === "paritypay_refresh");
    expect(rotated!.value).not.toBe(cookie!.value);
  });

  /**
   * 액세스 토큰이 메모리에만 있으면 새로고침마다 사라집니다. 앱이 쿠키로 조용히 되살려야 하고,
   * 그동안 보호된 경로가 로그인으로 튕기면 안 됩니다. 저장소 시험(jsdom)은 새로고침을 할 수 없습니다.
   */
  test("새로고침해도 로그인 상태가 유지되고, 저장소에는 토큰이 없다", async ({ page }) => {
    await signUpAndLinkAccount(page, "110-3333-4444");

    await page.reload();

    // 로그인 화면으로 튕기지 않고, 잔액이 보입니다 — 잔액 조회는 인증이 필요하므로 이것이 보이면
    // 액세스 토큰을 쿠키로 다시 받은 것입니다.
    await expect(page.getByRole("heading", { name: "페이머니" })).toBeVisible();
    expect(new URL(page.url()).pathname).toBe("/pay");
    expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.includes("token")))).toEqual([]);
  });

  /**
   * 토큰이 메모리에만 있으면 탭마다 뜰 때 재발급을 합니다. 탭 두 개가 **동시에** 뜨면 같은 쿠키로
   * 두 번 교환하고, 회전 때문에 늦은 쪽이 거절되어 로그아웃됩니다 — 이것이 액세스 토큰을 메모리로
   * 옮기는 데 반대한 이유였습니다. Web Locks가 같은 오리진의 탭을 줄 세워 막는지 실제 브라우저로 봅니다.
   */
  test("탭 두 개가 동시에 떠도 둘 다 로그인 상태다", async ({ page, context }) => {
    await signUpAndLinkAccount(page, "110-2222-3333");

    const [a, b] = await Promise.all([context.newPage(), context.newPage()]);
    // 그냥 두 탭을 동시에 열면 경쟁이 일어나지 않습니다 — 재발급 왕복이 1 ms라 두 탭의 요청이
    // 70 ms 간격으로 순차 처리됩니다. 잠금을 빼고 돌려도 통과했습니다. 그래서 경쟁을 **만듭니다**:
    // 재발급 요청은 서버에 바로 닿게 두되(회전은 그 순간 일어남) 응답을 700 ms 붙잡아 새 쿠키가
    // 브라우저에 늦게 도착하게 합니다. 먼저 간 탭이 응답을 받기 전에 다른 탭이 옛 쿠키로 재발급하면
    // 거절되고 로그아웃됩니다. 잠금이 있으면 늦은 탭은 먼저 간 탭의 응답이 올 때까지 기다립니다.
    const hold = async (route: import("@playwright/test").Route) => {
      const response = await route.fetch();
      await new Promise((resolve) => setTimeout(resolve, 700));
      await route.fulfill({ response });
    };
    await a.route("**/api/v1/auth/tokens/refresh", hold);
    await b.route("**/api/v1/auth/tokens/refresh", hold);
    await Promise.all([a.goto("/pay"), b.goto("/pay")]);

    // 둘 중 하나라도 로그인으로 튕기면 회전 경쟁에서 진 것입니다.
    await expect(a.getByRole("heading", { name: "페이머니" })).toBeVisible();
    await expect(b.getByRole("heading", { name: "페이머니" })).toBeVisible();
    expect(new URL(a.url()).pathname).toBe("/pay");
    expect(new URL(b.url()).pathname).toBe("/pay");
  });

  /**
   * ADR-011: 두 앱의 세션은 **호스트이름**으로 갈립니다.
   *
   * 처음 확인용 스택은 `localhost:8181`·`localhost:8182`였고, 쿠키는 포트를 구분하지 않아
   * 고객 앱의 리프레시 쿠키가 운영 콘솔로도 실려 갔습니다(실측 200). 이 시험은 호스트이름을
   * 가른 뒤 그것이 실제로 막히는지 봅니다. 두 URL의 호스트가 같으면 시험이 의미 없으므로 먼저
   * 그것부터 확인합니다.
   */
  test("고객 앱의 리프레시 쿠키는 운영 콘솔 호스트로 가지 않는다", async ({ page, context, baseURL }) => {
    const customerHost = new URL(baseURL!).hostname;
    const opsHost = new URL(OPS_URL).hostname;
    expect(customerHost, "두 앱이 같은 호스트이름이면 이 시험은 아무것도 보지 못합니다").not.toBe(opsHost);

    await signUpAndLinkAccount(page, "110-9999-0000");

    const cookie = (await context.cookies()).find((c) => c.name === "paritypay_refresh");
    expect(cookie!.domain).toBe(customerHost);

    // 같은 브라우저(같은 쿠키 항아리)로 운영 콘솔 호스트에서 재발급을 시도합니다.
    const ops = await context.newPage();
    await ops.goto(`${OPS_URL}/login`);
    const status = await ops.evaluate(async () => {
      const response = await fetch("/api/v1/auth/tokens/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
        credentials: "include",
      });
      return response.status;
    });

    // 쿠키가 건너갔다면 200입니다. 포트만 다를 때 실제로 그랬습니다.
    expect(status).toBe(400);
    // 운영 콘솔 호스트로 가는 요청에는 쿠키 자체가 없어야 합니다.
    expect((await context.cookies(OPS_URL)).find((c) => c.name === "paritypay_refresh")).toBeUndefined();
  });
});

test.describe("돈은 장애를 견딘다", () => {
  test.afterEach(async () => {
    // 다음 시험을 장애 모드 위에서 시작하지 않습니다.
    await setBankMode(await operatorToken(), "NORMAL");
  });

  test("응답이 유실돼도 충전은 실패가 아니고 정확히 한 번 반영된다", async ({ page }) => {
    const email = await signUpAndLinkAccount(page, "110-1111-2222");

    // 은행이 출금해 놓고 응답을 끊습니다. 돈은 이미 움직였습니다.
    await setBankMode(await operatorToken(), "TIMEOUT_AFTER_WITHDRAWAL");
    await submitTopUp(page);

    // **여기가 핵심입니다.** 실패라고 말하면 사용자는 다시 충전하고 이중 출금이 됩니다.
    await expect(page.getByRole("heading", { name: "처리 결과를 확인하고 있습니다" })).toBeVisible();
    await expect(page.getByText("충전 실패")).toHaveCount(0);
    // 다시 시도하도록 유도하는 버튼이 없어야 합니다.
    await expect(page.getByRole("button", { name: /충전$/ })).toHaveCount(0);

    // 확인 중에는 멱등 키를 쥐고 있어야 합니다. 버리면 결과를 알아낼 방법이 사라집니다.
    const heldWhileConfirming = await page.evaluate(() =>
      Object.keys(localStorage).filter((k) => k.startsWith("paritypay.intent.")),
    );
    expect(heldWhileConfirming.length).toBe(1);

    // 복구가 조회로 확정합니다. M-011에서 35초 근처로 측정됐습니다.
    await expect(page.getByRole("heading", { name: "충전 완료" })).toBeVisible({ timeout: 120_000 });
    const heldAfterSettle = await page.evaluate(() =>
      Object.keys(localStorage).filter((k) => k.startsWith("paritypay.intent.")),
    );
    expect(heldAfterSettle.length).toBe(0);

    // 응답이 유실됐지만 금액은 정확히 한 번만 들어왔습니다.
    const token = await customerToken(email);
    const client = await api();
    const wallet = (await (
      await client.get("/api/v1/wallets/me", { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { available: number };
    await client.dispose();
    expect(wallet.available).toBe(10_000);
  });

  test("결제를 연타해도 한 건이고, 주문번호만으로 원장까지 닿는다", async ({ page, context }) => {
    await signUpAndLinkAccount(page, "110-5555-6666");

    // 정상 충전으로 잔액을 만듭니다.
    await page.evaluate(() => {
      const quick = [...document.querySelectorAll("button")].find((b) => b.textContent?.includes("+30,000"));
      quick?.click();
    });
    await submitTopUp(page);
    await expect(page.getByRole("heading", { name: "충전 완료" })).toBeVisible({ timeout: 60_000 });

    // Shop에서 주문하고 결제 버튼을 연타합니다.
    await page.goto("/shop");
    // 전체 이동이라 토큰이 메모리에서 사라지고 앱이 쿠키로 되살립니다. 그동안은 버튼이 없습니다.
    await expect(page.getByRole("heading", { name: "상품" })).toBeVisible();
    await page.evaluate(() => (document.querySelectorAll("button")[0] as HTMLButtonElement).click());
    await expect(page.getByRole("heading", { name: "결제" })).toBeVisible();
    const orderId = (await page.evaluate(() => location.pathname)).split("/").pop()!;

    await page.evaluate(() => {
      const pay = [...document.querySelectorAll("button")].find((b) => b.textContent?.includes("결제"));
      pay?.click();
      pay?.click();
      pay?.click();
    });
    await expect(page.getByText("구매확정")).toBeVisible({ timeout: 60_000 });

    // 운영 콘솔에서 **주문번호로** 찾습니다. 고객이 들고 오는 것이 이것입니다.
    const ops = await context.newPage();
    await ops.goto(`${OPS_URL}/login`);
    await ops.fill('input[type="email"]', OPS_EMAIL);
    await ops.fill('input[type="password"]', OPS_PASSWORD);
    await ops.evaluate(() => document.querySelector("form")?.requestSubmit());
    await expect(ops.getByRole("heading", { name: "거래 검색" })).toBeVisible();

    await ops.fill('input[aria-label="식별자"]', orderId);
    await ops.evaluate(() => document.querySelector("form")?.requestSubmit());
    await ops.getByTestId("reference").first().click();

    // 결함 J: 주문번호로 들어오면 원장 줄이 빠져 있었습니다. 결제 ID로만 나왔습니다.
    await expect(ops.getByTestId("timeline")).toBeVisible();
    await expect(ops.getByRole("button", { name: "원장" })).toBeVisible();

    await ops.getByRole("button", { name: "원장" }).click();
    await expect(ops.getByTestId("balanced")).toHaveText("차변 = 대변");

    // 서버에 남은 결제는 한 건이어야 합니다. 연타는 UX가 막고, 정확성은 멱등 키가 지킵니다.
    const token = await operatorToken();
    const client = await api();
    const resolved = (await (
      await client.get(`/api/v1/admin/transactions/resolve?query=${orderId}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json()) as { references: unknown[] };
    expect(resolved.references).toHaveLength(1);

    // 장애를 겪은 뒤에도 불변조건은 전부 0이어야 합니다.
    const invariants = (await (
      await client.get("/api/v1/admin/invariants", { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { values: { name: string; value: number | null }[] };
    await client.dispose();
    for (const value of invariants.values.filter((v) => v.name.startsWith("paritypay.invariant."))) {
      expect(value.value, value.name).toBe(0);
    }
  });
});
