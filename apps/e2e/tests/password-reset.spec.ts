/**
 * E2E — 비밀번호 재설정이 메일을 거쳐 끝까지 갑니다.
 *
 * 백엔드 시험은 메일 전송을 목으로 두고(`RecordedPasswordResetDelivery`), 프론트 시험은 API를
 * 목으로 둡니다. 둘 다 통과해도 **실제 SMTP로 메일이 나가고 그 안의 링크가 화면에 닿는지**는 아무도
 * 보지 못합니다. 링크 템플릿이 틀리거나, 토큰이 URL 인코딩에서 깨지거나, 메일이 커밋 전에 나가면
 * 여기서 드러납니다. Mailpit이 SMTP를 받고 API로 돌려줍니다.
 *
 * 근거: reports/12 §12 다음 확장 11, docs/05-technical-design.md §11
 */
import { expect, request, test } from "@playwright/test";

const MAILPIT_URL = process.env["E2E_MAILPIT_URL"] ?? "http://localhost:8025";
const OLD_PASSWORD = "password1234";
const NEW_PASSWORD = "brand-new-pass-5678";

/** Mailpit API에서 이 주소로 온 마지막 메일 본문을 가져옵니다. 메일은 비동기라 잠시 기다립니다. */
async function lastMailTo(email: string): Promise<string> {
  const mailpit = await request.newContext({ baseURL: MAILPIT_URL });
  try {
    for (let attempt = 0; attempt < 20; attempt++) {
      const search = await mailpit.get(`/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
      const { messages } = (await search.json()) as { messages: { ID: string }[] };
      if (messages.length > 0) {
        const message = await mailpit.get(`/api/v1/message/${messages[0]!.ID}`);
        return ((await message.json()) as { Text: string }).Text;
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`${email}로 온 메일이 없습니다 (${MAILPIT_URL})`);
  } finally {
    await mailpit.dispose();
  }
}

test("재설정 메일의 링크로 새 비밀번호를 정하고 로그인한다", async ({ page }) => {
  const email = `reset-${Date.now()}@example.com`;
  await page.goto("/signup");
  await page.fill('input[type="email"]', email);
  await page.fill('input[type="password"]', OLD_PASSWORD);
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
  await expect(page.getByRole("heading", { name: "페이머니" })).toBeVisible();

  // 새 브라우저처럼 — 세션 없이 비밀번호 찾기로 들어갑니다.
  await page.context().clearCookies();
  await page.goto("/forgot");
  await page.fill('input[type="email"]', email);
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
  await expect(page.getByTestId("reset-requested")).toBeVisible();

  // 메일이 실제로 나갔고, 그 안의 링크가 이 앱을 가리킵니다.
  const body = await lastMailTo(email);
  const link = body.match(/https?:\/\/\S+\/reset\?token=\S+/)?.[0];
  expect(link, `메일에 재설정 링크가 없습니다:\n${body}`).toBeDefined();
  expect(body).not.toContain(OLD_PASSWORD);

  await page.goto(link!);
  await page.fill('input[type="password"]', NEW_PASSWORD);
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
  await expect(page.getByTestId("reset-done")).toBeVisible();

  // 토큰은 한 번만 씁니다. 같은 링크를 다시 쓰면 거절되어야 합니다.
  await page.goto(link!);
  await page.fill('input[type="password"]', "another-pass-9999");
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
  await expect(page.getByTestId("reset-failed")).toBeVisible();

  // 새 비밀번호로 로그인되고, 옛 비밀번호는 통하지 않습니다.
  await page.goto("/login");
  await page.fill('input[type="email"]', email);
  await page.fill('input[type="password"]', OLD_PASSWORD);
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
  await expect(page.getByRole("alert")).toBeVisible();

  await page.fill('input[type="password"]', NEW_PASSWORD);
  await page.evaluate(() => document.querySelector("form")?.requestSubmit());
  await expect(page.getByRole("heading", { name: "페이머니" })).toBeVisible();
});
