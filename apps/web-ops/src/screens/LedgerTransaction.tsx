/**
 * OPS 원장 탐색기 (DOC-15 §4.4).
 *
 * 차변·대변을 나란히 놓고 합계가 같은지 보여 줍니다. **합계와 균형 여부는 서버가 계산한 값**을
 * 그대로 씁니다 — 화면이 다시 더하면 그 계산이 원장과 어긋날 수 있고, 어긋난 쪽이 화면이라도
 * 사용자는 화면을 믿습니다.
 *
 * **원장을 고치는 버튼은 없습니다.** 확정 원장은 트리거가 수정을 막고(INV-006), 보정은 새
 * 분개로만 합니다(JE-012). API에도 쓰기 경로가 없습니다.
 */
import { getLedgerTransaction, type LedgerTransactionResponse } from "@paritypay/api-client";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { formatInstant, formatWon } from "../format";

export function LedgerTransaction({ transactionId }: { transactionId: string }) {
  const ledger = useQuery<LedgerTransactionResponse>({
    queryKey: ["ledger", transactionId],
    queryFn: () => getLedgerTransaction(api, transactionId),
  });

  if (ledger.isPending) {
    return <p>원장을 불러오는 중입니다.</p>;
  }
  if (ledger.isError) {
    return <p role="alert">원장 거래를 찾을 수 없습니다.</p>;
  }

  const view = ledger.data;
  return (
    <section data-testid="ledger">
      <h2>원장 거래</h2>
      <dl>
        <dt>유형</dt>
        <dd>{view.transactionType}</dd>
        <dt>참조</dt>
        <dd>
          {view.referenceType} {view.referenceId}
        </dd>
        <dt>전기 시각</dt>
        <dd>{view.effectiveAt === undefined ? "" : formatInstant(view.effectiveAt)}</dd>
        {view.reversalOfTransactionId !== undefined && view.reversalOfTransactionId !== null && (
          <>
            <dt>역분개 대상</dt>
            <dd data-testid="reversal-of">{view.reversalOfTransactionId}</dd>
          </>
        )}
      </dl>

      <table>
        <thead>
          <tr>
            <th>계정</th>
            <th>차변</th>
            <th>대변</th>
          </tr>
        </thead>
        <tbody>
          {(view.entries ?? []).map((entry) => (
            <tr key={entry.entryId} data-testid="entry">
              <td>{entry.accountCode}</td>
              <td data-testid="debit">
                {entry.direction === "DEBIT" ? formatWon(entry.amount ?? 0) : ""}
              </td>
              <td data-testid="credit">
                {entry.direction === "CREDIT" ? formatWon(entry.amount ?? 0) : ""}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th>합계</th>
            <td data-testid="debit-total">{formatWon(view.debitTotal ?? 0)}</td>
            <td data-testid="credit-total">{formatWon(view.creditTotal ?? 0)}</td>
          </tr>
        </tfoot>
      </table>

      {/* INV-001은 DB가 지키지만, 지켜지고 있다는 사실이 화면에 보여야 의미가 있습니다. */}
      <p data-testid="balanced">{view.balanced === true ? "차변 = 대변" : "차변 ≠ 대변 — 즉시 확인 필요"}</p>
    </section>
  );
}
