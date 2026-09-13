/**
 * 새 비밀번호 설정. 메일의 링크(`/reset?token=…`)로 들어옵니다.
 *
 * 토큰이 없거나·썼거나·만료됐거나·무효화됐으면 서버는 전부 같은 400입니다. 어느 쪽인지 알려 주면
 * 토큰을 추측하는 사람에게 힌트가 되므로 화면도 한 문구로 답하고 다시 요청하도록 안내합니다.
 */
import { useMutation } from "@tanstack/react-query";
import { confirmPasswordReset } from "@paritypay/api-client";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api } from "../api";

export function ResetPassword() {
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const submit = useMutation({ mutationFn: () => confirmPasswordReset(api, token, password) });

  if (token === "") {
    return (
      <section>
        <h1>링크가 올바르지 않습니다</h1>
        <Link to="/forgot">다시 요청하기</Link>
      </section>
    );
  }
  if (submit.isSuccess) {
    return (
      <section>
        <h1>비밀번호를 바꿨습니다</h1>
        <p data-testid="reset-done">다른 기기의 로그인은 모두 끊겼습니다. 새 비밀번호로 로그인하세요.</p>
        <Link to="/login">로그인으로</Link>
      </section>
    );
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit.mutate();
      }}
    >
      <h1>새 비밀번호</h1>
      <label>
        새 비밀번호
        <input
          type="password"
          autoComplete="new-password"
          minLength={8}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
      </label>
      <button type="submit" disabled={submit.isPending}>
        바꾸기
      </button>
      {submit.isError && (
        <p role="alert" data-testid="reset-failed">
          이 링크는 더 이상 쓸 수 없습니다. <Link to="/forgot">다시 요청</Link>하세요.
        </p>
      )}
    </form>
  );
}
