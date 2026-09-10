/**
 * SCR-08 거래내역 (FR-008, DOC-15 §1.3).
 *
 * 이 목록은 **Kafka 소비자가 만드는 프로젝션**입니다(ADR-006). 결제가 201로 성공한 직후에도
 * 아직 없을 수 있습니다.
 *
 * 그래서 두 가지를 지킵니다.
 *
 * - 목록에 없다는 것을 "일어나지 않았다"로 표현하지 않습니다.
 * - 쓰기 응답으로 이미 아는 거래는 **"반영 중"으로 표시해 먼저 보여 주고**, 프로젝션이 따라오면
 *   조용히 대체합니다. 표시 없이 섞으면 사용자는 목록을 진실로 믿습니다.
 *
 * 커서는 **불투명 문자열**입니다. 만들거나 파싱하지 않습니다. M-008에서 110만 건에서도 깊이와
 * 무관하게 0.06~0.09 ms인 것을 확인했으므로 무한 스크롤이 성능 문제를 만들지 않습니다.
 *
 * 근거: docs/14-frontend-design.md §3 FE-004, reports/11 M-008
 */
import { listTransactions, type TransactionPageResponse } from "@paritypay/api-client";
import { useInfiniteQuery } from "@tanstack/react-query";
import { api } from "../api";
import { formatInstant, formatWon } from "../format";

/** 서버 프로젝션이 아직 모르는, 우리가 방금 만든 거래입니다. */
export interface OptimisticEntry {
  readonly referenceId: string;
  readonly type: string;
  readonly direction: "CREDIT" | "DEBIT";
  readonly amount: number;
  readonly occurredAt: string;
}

const TYPE_LABEL: Record<string, string> = {
  TOP_UP: "페이머니 충전",
  PAYMENT: "상품 결제",
  PAYMENT_CANCELLATION: "결제 취소",
};

export function Transactions({
  walletId,
  optimistic = [],
}: {
  walletId: string;
  optimistic?: readonly OptimisticEntry[];
}) {
  const page = useInfiniteQuery<TransactionPageResponse>({
    queryKey: ["transactions", walletId],
    queryFn: ({ pageParam }) => listTransactions(api, walletId, pageParam as string | undefined),
    initialPageParam: undefined,
    // `nextCursor`가 없으면 마지막 쪽입니다. 우리가 커서를 만들지 않습니다.
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });

  if (page.isPending) {
    return <p>거래내역을 불러오는 중입니다.</p>;
  }
  if (page.isError) {
    return <p role="alert">거래내역을 불러오지 못했습니다.</p>;
  }

  const settled = page.data.pages.flatMap((p) => p.transactions ?? []);
  const settledReferences = new Set(settled.map((t) => t.referenceId));
  // 프로젝션이 이미 따라잡은 항목은 낙관적 목록에서 뺍니다.
  const stillPending = optimistic.filter((o) => !settledReferences.has(o.referenceId));

  return (
    <section>
      <h1>거래내역</h1>
      <ul>
        {stillPending.map((entry) => (
          <li key={entry.referenceId} data-testid="pending-entry">
            <span>{TYPE_LABEL[entry.type] ?? entry.type}</span>
            <span>
              {entry.direction === "CREDIT" ? "+" : "-"}
              {formatWon(entry.amount)}
            </span>
            {/* 목록에 아직 없다는 사실을 숨기지 않습니다. */}
            <small data-testid="pending-badge">반영 중</small>
          </li>
        ))}
        {settled.map((entry) => (
          <li key={entry.transactionId} data-testid="entry">
            <span>{TYPE_LABEL[entry.type ?? ""] ?? entry.type}</span>
            <span>
              {entry.direction === "CREDIT" ? "+" : "-"}
              {formatWon(entry.amount ?? 0)}
            </span>
            <small>{entry.occurredAt === undefined ? "" : formatInstant(entry.occurredAt)}</small>
          </li>
        ))}
      </ul>
      {settled.length === 0 && stillPending.length === 0 && <p>거래내역이 없습니다.</p>}
      {page.hasNextPage && (
        <button type="button" data-testid="more" onClick={() => void page.fetchNextPage()}>
          더 보기
        </button>
      )}
    </section>
  );
}
