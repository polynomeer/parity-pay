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
import { expect, test, type Page } from "@playwright/test";

const OPS_URL = process.env["E2E_OPS_URL"] ?? "http://localhost:5174";
const API_URL = process.env["E2E_API_URL"] ?? "http://localhost:8080";
const OPS_EMAIL = "ops-operator@paritypay.local";
const OPS_PASSWORD = "local-ops-password";

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
async function signUpAndLinkAccount(page: Page, accountNumber: string): Promise<void> {
  await page.goto("/signup");
  await submitForm(page, {
    'input[type="email"]': `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`,
    'input[type="password"]': "password1234",
  });
  // 가입이 끝나면 홈으로 넘어갑니다. 여기까지 와야 토큰이 저장되어 있습니다.
  await expect(page.getByRole("heading", { name: "페이머니" })).toBeVisible();

  await page.goto("/pay");
  await submitForm(page, { "form input": accountNumber });
  await expect(page.getByRole("heading", { name: "충전" })).toBeVisible();
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

/** 운영자 토큰을 API로 직접 받습니다. 장애 주입은 화면이 아니라 계약을 확인하는 단계입니다. */
async function operatorToken(): Promise<string> {
  const response = await fetch(`${API_URL}/api/v1/auth/tokens`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: OPS_EMAIL, password: OPS_PASSWORD }),
  });
  const body = (await response.json()) as { accessToken: string };
  return body.accessToken;
}

async function setBankMode(token: string, mode: string): Promise<void> {
  const response = await fetch(`${API_URL}/api/v1/admin/mock-bank/mode`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ mode }),
  });
  expect(response.status).toBe(204);
}

test.describe("돈은 장애를 견딘다", () => {
  test.afterEach(async () => {
    // 다음 시험을 장애 모드 위에서 시작하지 않습니다.
    await setBankMode(await operatorToken(), "NORMAL");
  });

  test("응답이 유실돼도 충전은 실패가 아니고 정확히 한 번 반영된다", async ({ page }) => {
    await signUpAndLinkAccount(page, "110-1111-2222");

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
    const token = await page.evaluate(
      () => (JSON.parse(localStorage.getItem("paritypay.tokens") ?? "{}") as { accessToken: string }).accessToken,
    );
    const wallet = (await (
      await fetch(`${API_URL}/api/v1/wallets/me`, { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { available: number };
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
    const token = await ops.evaluate(
      () => (JSON.parse(localStorage.getItem("paritypay.tokens") ?? "{}") as { accessToken: string }).accessToken,
    );
    const resolved = (await (
      await fetch(`${API_URL}/api/v1/admin/transactions/resolve?query=${orderId}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).json()) as { references: unknown[] };
    expect(resolved.references).toHaveLength(1);

    // 장애를 겪은 뒤에도 불변조건은 전부 0이어야 합니다.
    const invariants = (await (
      await fetch(`${API_URL}/api/v1/admin/invariants`, { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { values: { name: string; value: number | null }[] };
    for (const value of invariants.values.filter((v) => v.name.startsWith("paritypay.invariant."))) {
      expect(value.value, value.name).toBe(0);
    }
  });
});
