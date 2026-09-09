/** SCR-02 로그인. 실패 잠금은 "비밀번호 틀림"과 다른 문구여야 합니다 (FE-008). */
import { useMutation } from "@tanstack/react-query";
import { ApiError, login, userMessage } from "@paritypay/api-client";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, auth } from "../api";

export function Login() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const submit = useMutation({
    // 로그인은 멱등 키가 없습니다. 금융 효과가 없기 때문입니다.
    mutationFn: () => login(api, auth, { email, password }),
    onSuccess: () => navigate("/"),
  });

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit.mutate();
      }}
    >
      <h1>로그인</h1>
      <label>
        이메일
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </label>
      <label>
        비밀번호
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
      </label>
      <button type="submit" disabled={submit.isPending}>
        {submit.isPending ? "확인 중" : "로그인"}
      </button>
      {submit.isError && (
        <p role="alert">
          {submit.error instanceof ApiError
            ? userMessage(submit.error.code)
            : "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요."}
        </p>
      )}
    </form>
  );
}
