/**
 * 비밀번호 찾기.
 *
 * 응답은 가입 여부와 무관하게 같습니다. 화면도 그렇게 씁니다 — "가입된 주소가 아닙니다"라고
 * 말해 주면 이 화면이 가입 여부 조회기가 됩니다.
 */
import { useMutation } from "@tanstack/react-query";
import { requestPasswordReset } from "@paritypay/api-client";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";

export function ForgotPassword() {
  const [email, setEmail] = useState("");
  const submit = useMutation({ mutationFn: () => requestPasswordReset(api, email) });

  if (submit.isSuccess) {
    return (
      <section className="stack">
        <h1>메일을 확인하세요</h1>
        <p className="notice notice--muted" data-testid="reset-requested">
          {email}로 가입된 계정이 있다면 재설정 링크를 보냈습니다. 링크는 30분 동안 유효합니다.
        </p>
        <p className="auth__foot">
          <Link to="/login">로그인으로</Link>
        </p>
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
      <div className="stack--tight stack">
        <h1>비밀번호 찾기</h1>
        <p className="muted">가입한 이메일로 재설정 링크를 보내 드립니다.</p>
      </div>
      <label>
        이메일
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </label>
      <button type="submit" disabled={submit.isPending}>
        재설정 링크 보내기
      </button>
      {submit.isError && <p role="alert">요청을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.</p>}
      <p className="auth__foot">
        <Link to="/login">로그인으로</Link>
      </p>
    </form>
  );
}
