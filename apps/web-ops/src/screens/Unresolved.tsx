/**
 * OPS-02·03 미확정 거래 목록과 재조회 (DOC-15 §4.5).
 *
 * **성공·실패를 운영자가 고르는 기능은 두지 않습니다.** 버튼이 하는 일은 조회를 앞당기는 것뿐이고,
 * 확정은 서버의 복구 작업이 합니다. DOC-15가 같은 말을 했고, 백엔드도 그렇게 만들어져 있습니다.
 *
 * 체류 시간을 함께 보여 줍니다. M-011에서 확정까지 35~45초로 측정됐으므로, 그보다 오래 남아
 * 있는 건은 무언가 다른 일이 벌어지고 있다는 뜻입니다.
 */
import {
  listUnresolvedPayments,
  listUnresolvedTopUps,
  resolvePaymentNow,
  resolveTopUpNow,
} from "@paritypay/api-client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { formatWon } from "../format";
import { StatusBadge } from "../StatusBadge";

export function Unresolved() {
  const queryClient = useQueryClient();
  const topUps = useQuery({ queryKey: ["unresolved", "top-ups"], queryFn: () => listUnresolvedTopUps(api) });
  const payments = useQuery({ queryKey: ["unresolved", "payments"], queryFn: () => listUnresolvedPayments(api) });

  const resolve = useMutation({
    mutationFn: ({ kind, id }: { kind: "top-up" | "payment"; id: string }) =>
      kind === "top-up" ? resolveTopUpNow(api, id) : resolvePaymentNow(api, id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["unresolved"] }),
  });

  const rows = [
    ...(topUps.data ?? []).map((t) => ({
      kind: "top-up" as const,
      id: t.topUpId ?? "",
      status: t.status ?? "",
      amount: t.requestedAmount ?? 0,
      attempts: t.attemptCount ?? 0,
      manualReview: t.requiresManualReview ?? false,
    })),
    ...(payments.data ?? []).map((p) => ({
      kind: "payment" as const,
      id: p.paymentId ?? "",
      status: p.status ?? "",
      amount: p.requestedAmount ?? 0,
      attempts: p.attemptCount ?? 0,
      manualReview: p.requiresManualReview ?? false,
    })),
  ];

  return (
    <section className="page">
      <div className="page-head">
        <h1>미확정 거래</h1>
        <p>운영자는 결과를 고르지 않습니다. 버튼은 외부 상태 조회를 앞당길 뿐이고, 확정은 복구 작업이 합니다.</p>
      </div>
      {rows.length === 0 ? (
        <p className="empty card" data-testid="empty">
          미확정 거래가 없습니다.
        </p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>종류</th>
                <th>ID</th>
                <th>상태</th>
                <th className="num">금액</th>
                <th className="num">조회 시도</th>
                <th>수동 검토</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.kind}-${row.id}`} data-testid="unresolved-row">
                  <td>{row.kind === "top-up" ? "충전" : "결제"}</td>
                  <td className="id">{row.id}</td>
                  <td>
                    <StatusBadge status={row.status} />
                  </td>
                  <td className="num money">{formatWon(row.amount)}</td>
                  <td className="num">{row.attempts}</td>
                  <td>{row.manualReview ? <span className="badge badge--warn">필요</span> : ""}</td>
                  <td>
                    <button
                      type="button"
                      className="btn--sm"
                      data-testid="resolve"
                      onClick={() => resolve.mutate({ kind: row.kind, id: row.id })}
                    >
                      {/* "성공 처리"가 아니라 "다시 조회"입니다. 운영자는 결과를 고르지 않습니다. */}
                      외부 상태 다시 조회
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
