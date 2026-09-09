/** SCR-01 가입 (FR-001). 가입에 성공하면 지갑이 함께 만들어집니다. */
import { useMutation } from "@tanstack/react-query";
import { ApiError, login, register, userMessage } from "@paritypay/api-client";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, auth } from "../api";

export function SignUp() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const submit = useMutation({
    mutationFn: async () => {
      await register(api, { email, password });
      // 가입 직후 바로 쓸 수 있게 로그인까지 합니다. 지갑 ID는 보관하지 않습니다 —
      // `/wallets/me`가 토큰만으로 찾아 줍니다.
      return login(api, auth, { email, password });
    },
    onSuccess: () => navigate("/"),
  });

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit.mutate();
      }}
    >
      <h1>가입</h1>
      <label>
        이메일
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </label>
      <label>
        비밀번호
        <input
          type="password"
          minLength={8}
          maxLength={72}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
      </label>
      <button type="submit" disabled={submit.isPending}>
        {submit.isPending ? "만드는 중" : "가입"}
      </button>
      {submit.isError && (
        <p role="alert">
          {submit.error instanceof ApiError
            ? userMessage(submit.error.code)
            : "가입하지 못했습니다. 잠시 후 다시 시도해 주세요."}
        </p>
      )}
    </form>
  );
}
