/**
 * 비밀번호 찾기·재설정 화면.
 *
 * 보는 것은 두 가지입니다. 요청 화면이 가입 여부를 새지 않는가(어느 주소든 같은 문구), 재설정 실패가
 * 이유를 갈라 말하지 않는가(한 문구 + 다시 요청). 실제 메일 왕복은 E2E가 Mailpit으로 봅니다.
 */
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { ForgotPassword } from "./ForgotPassword";
import { ResetPassword } from "./ResetPassword";
import { createQueryClient } from "../queryClient";

const requested: string[] = [];
const server = setupServer(
  http.post("*/api/v1/auth/password-reset", async ({ request }) => {
    requested.push(((await request.json()) as { email: string }).email);
    return new HttpResponse(null, { status: 202 });
  }),
  http.post("*/api/v1/auth/password-reset/confirm", async ({ request }) => {
    const { token } = (await request.json()) as { token: string };
    return token === "good"
      ? new HttpResponse(null, { status: 204 })
      : HttpResponse.json({ code: "INVALID_REQUEST", message: "reset token is not valid" }, { status: 400 });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  requested.length = 0;
});
afterAll(() => server.close());

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/forgot" element={<ForgotPassword />} />
          <Route path="/reset" element={<ResetPassword />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("비밀번호 찾기", () => {
  it("가입되지 않은 주소에도 같은 문구를 보여 준다 — 가입 여부를 새지 않는다", async () => {
    renderAt("/forgot");
    await userEvent.type(screen.getByLabelText("이메일"), "nobody@example.com");
    await userEvent.click(screen.getByRole("button", { name: "재설정 링크 보내기" }));

    const notice = await screen.findByTestId("reset-requested");
    expect(notice).toHaveTextContent("가입된 계정이 있다면");
    expect(requested).toEqual(["nobody@example.com"]);
  });
});

describe("새 비밀번호", () => {
  it("토큰이 있으면 새 비밀번호를 정하고 끝난다", async () => {
    renderAt("/reset?token=good");
    await userEvent.type(screen.getByLabelText("새 비밀번호"), "brand-new-pass");
    await userEvent.click(screen.getByRole("button", { name: "바꾸기" }));

    expect(await screen.findByTestId("reset-done")).toBeVisible();
  });

  it("쓸 수 없는 토큰은 이유를 가르지 않고 다시 요청하게 한다", async () => {
    renderAt("/reset?token=used-or-expired");
    await userEvent.type(screen.getByLabelText("새 비밀번호"), "brand-new-pass");
    await userEvent.click(screen.getByRole("button", { name: "바꾸기" }));

    const failed = await screen.findByTestId("reset-failed");
    expect(failed).toHaveTextContent("더 이상 쓸 수 없습니다");
    expect(failed).not.toHaveTextContent(/만료|이미/);
  });

  it("토큰 없이 들어오면 폼 대신 다시 요청하기를 보여 준다", () => {
    renderAt("/reset");
    expect(screen.getByRole("link", { name: "다시 요청하기" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "바꾸기" })).toBeNull();
  });
});
