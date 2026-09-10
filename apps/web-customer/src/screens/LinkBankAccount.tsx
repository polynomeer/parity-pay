/** SCR-04 계좌 연결 (FR-002). 계좌번호는 화면에도 전체를 남기지 않습니다. */
import { ApiError, userMessage } from "@paritypay/api-client";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";

export function LinkBankAccount({ onLinked }: { onLinked: (bankAccountId: string) => void }) {
  const [accountNumber, setAccountNumber] = useState("");

  const link = useMutation({
    // 계좌 연결은 금융 효과가 없어 멱등 키를 요구하지 않습니다.
    mutationFn: async () => {
      const response = await api.publicWrite<{ bankAccountId: string }>("/api/v1/bank-accounts", {
        bankCode: "004",
        accountNumber,
        initialBalance: 1_000_000,
      });
      return response.data.bankAccountId;
    },
    onSuccess: onLinked,
  });

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        link.mutate();
      }}
    >
      <h2>계좌 연결</h2>
      <label>
        계좌번호
        <input
          value={accountNumber}
          onChange={(e) => setAccountNumber(e.target.value)}
          maxLength={20}
          required
        />
      </label>
      <button type="submit" disabled={link.isPending}>
        연결
      </button>
      {link.isError && (
        <p role="alert">
          {link.error instanceof ApiError ? userMessage(link.error.code) : "연결하지 못했습니다."}
        </p>
      )}
    </form>
  );
}
