/**
 * OPS-05·06 대사 워크벤치와 보정 분개 (DOC-15 §4.7·§4.8).
 *
 * 두 가지를 화면이 강제합니다.
 *
 * - **FE-010 이중 승인**: 자기 요청을 자기가 승인할 수 없습니다. 서버가 거부하지만 화면도 미리
 *   막습니다 — 서버가 막으니 괜찮다고 두면 운영자는 실패한 뒤에야 알게 됩니다.
 * - **FE-011 사유 필수**: 기본값이나 자동 채움을 넣지 않습니다. 감사 로그에 남는 값입니다.
 *
 * **FE-012 금액을 직접 고치는 입력은 없습니다.** 보정은 차변·대변 계정을 지정한 새 분개입니다.
 */
import {
  ADJUSTMENT_ACCOUNTS,
  listOpenMismatches,
  requestAdjustment,
  type AdjustmentAccount,
  type MismatchResponse,
} from "@paritypay/api-client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, tokenStore } from "../api";
import { formatWon } from "../format";

const TYPE_LABEL: Record<string, string> = {
  STATUS_MISMATCH: "상태 불일치",
  EXTERNAL_ONLY: "외부에만 존재",
  INTERNAL_ONLY: "내부에만 존재",
  AMOUNT_MISMATCH: "금액 불일치",
  LEDGER_MISSING: "원장 누락",
  MATCHED: "일치",
};

export function Reconciliation() {
  const mismatches = useQuery<MismatchResponse[]>({
    queryKey: ["mismatches"],
    queryFn: () => listOpenMismatches(api),
  });
  const [openId, setOpenId] = useState<string | null>(null);

  if (mismatches.isPending) {
    return <p>불일치를 불러오는 중입니다.</p>;
  }
  if (mismatches.isError) {
    return <p role="alert">불일치를 불러오지 못했습니다.</p>;
  }

  return (
    <section>
      <h1>대사 워크벤치</h1>
      {mismatches.data.length === 0 ? (
        <p data-testid="no-mismatch">미해결 불일치가 없습니다.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>유형</th>
              <th>내부</th>
              <th>외부</th>
              <th>차이</th>
              <th>상태</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {mismatches.data.map((mismatch) => (
              <tr key={mismatch.mismatchId} data-testid="mismatch-row">
                <td>{TYPE_LABEL[mismatch.type ?? ""] ?? mismatch.type}</td>
                <td>{mismatch.internalAmount === null ? "없음" : formatWon(mismatch.internalAmount ?? 0)}</td>
                <td>{mismatch.externalAmount === null ? "없음" : formatWon(mismatch.externalAmount ?? 0)}</td>
                <td>{formatWon(mismatch.amountDifference ?? 0)}</td>
                <td>{mismatch.resolutionStatus}</td>
                <td>
                  <button type="button" data-testid="open-adjust" onClick={() => setOpenId(mismatch.mismatchId ?? null)}>
                    보정 분개
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {openId !== null && <AdjustmentForm mismatchId={openId} />}
    </section>
  );
}

function AdjustmentForm({ mismatchId }: { mismatchId: string }) {
  const queryClient = useQueryClient();
  const requesterId = tokenStore.read()?.memberId ?? "";
  const [approverId, setApproverId] = useState("");
  const [reason, setReason] = useState("");
  const [amount, setAmount] = useState(0);
  const [debitAccount, setDebitAccount] = useState<AdjustmentAccount | "">("");
  const [creditAccount, setCreditAccount] = useState<AdjustmentAccount | "">("");

  // 자기가 자기를 승인할 수 없습니다. 서버도 막지만 버튼을 누르기 전에 알려 줍니다.
  const selfApproval = approverId !== "" && approverId === requesterId;
  const complete = approverId !== "" && reason.trim() !== "" && amount > 0 && debitAccount !== "" && creditAccount !== "";

  const submit = useMutation({
    mutationFn: () =>
      requestAdjustment(api, mismatchId, approverId, {
        amount,
        reason,
        debitAccount: debitAccount as AdjustmentAccount,
        creditAccount: creditAccount as AdjustmentAccount,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["mismatches"] }),
  });

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit.mutate();
      }}
    >
      <h2>보정 분개 요청</h2>
      {/* 금액을 '고치는' 입력이 아니라 새 분개의 금액입니다. */}
      <label>
        차변 계정
        {/* 자유 입력이 아닙니다. 계약에 있는 계정만 고를 수 있습니다. */}
        <select
          data-testid="debit"
          value={debitAccount}
          onChange={(e) => setDebitAccount(e.target.value as AdjustmentAccount)}
          required
        >
          <option value="">선택</option>
          {ADJUSTMENT_ACCOUNTS.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </label>
      <label>
        대변 계정
        <select
          data-testid="credit"
          value={creditAccount}
          onChange={(e) => setCreditAccount(e.target.value as AdjustmentAccount)}
          required
        >
          <option value="">선택</option>
          {ADJUSTMENT_ACCOUNTS.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </label>
      <label>
        금액
        <input
          type="number"
          data-testid="amount"
          min={1}
          value={amount}
          onChange={(e) => setAmount(Number(e.target.value))}
          required
        />
      </label>
      <label>
        사유
        {/* 기본값을 넣지 않습니다. 감사 로그에 남는 값입니다. */}
        <input data-testid="reason" value={reason} onChange={(e) => setReason(e.target.value)} required />
      </label>
      <label>
        승인자
        <input data-testid="approver" value={approverId} onChange={(e) => setApproverId(e.target.value)} required />
      </label>

      {selfApproval && (
        <p role="alert" data-testid="self-approval">
          자기 요청은 자기가 승인할 수 없습니다. 다른 승인자를 지정하십시오.
        </p>
      )}
      <button type="submit" data-testid="submit-adjust" disabled={!complete || selfApproval || submit.isPending}>
        보정 요청
      </button>
      {submit.isSuccess && <p data-testid="adjusted">보정 분개를 만들었습니다.</p>}
    </form>
  );
}
