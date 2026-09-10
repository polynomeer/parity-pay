/**
 * OPS-01 통합 거래 검색 · 타임라인 (DOC-15 §4.2).
 *
 * **운영 콘솔의 중심 화면입니다.** 고객이 어떤 식별자를 들고 오든 하나의 입력으로 시작합니다.
 *
 * 검색과 타임라인이 두 단계인 이유가 있습니다. 결제 ID·주문 ID는 거래 하나를 가리키지만 지갑
 * ID와 회원 ID는 거래 **여럿**을 가리킵니다. 하나로 합칠 수 없는 것을 합친 척하지 않고, 후보를
 * 보여 준 뒤 고르게 합니다.
 *
 * 근거: docs/16-ui-implementation-plan.md §4 FE-M4
 */
import { getTimeline, resolveIdentifier, type SearchResult, type Timeline } from "@paritypay/api-client";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { formatInstant, formatWon } from "../format";

const KIND_LABEL: Record<string, string> = {
  PAYMENT: "결제",
  TOP_UP: "충전",
  CANCELLATION: "취소",
  SETTLEMENT: "정산",
  ORDER: "주문",
  WALLET: "지갑",
  MEMBER: "회원",
  EVENT: "이벤트",
  LEDGER_TRANSACTION: "원장 거래",
  UNKNOWN: "알 수 없음",
};

export function Explorer() {
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const search = useQuery<SearchResult>({
    queryKey: ["resolve", submitted],
    queryFn: () => resolveIdentifier(api, submitted!),
    enabled: submitted !== null,
  });

  const timeline = useQuery<Timeline>({
    queryKey: ["timeline", selected],
    queryFn: () => getTimeline(api, selected!),
    enabled: selected !== null,
  });

  return (
    <section>
      <h1>거래 검색</h1>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          setSelected(null);
          setSubmitted(query.trim());
        }}
      >
        <input
          aria-label="식별자"
          data-testid="query"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="주문번호 · 결제 ID · 지갑 ID · 회원 ID · 이벤트 ID"
        />
        <button type="submit" data-testid="search">
          찾기
        </button>
      </form>

      {search.data !== undefined && (
        <div data-testid="result">
          <p>
            입력값은 <strong data-testid="kind">{KIND_LABEL[search.data.kind ?? ""] ?? search.data.kind}</strong>
            입니다.
          </p>
          {(search.data.references ?? []).length === 0 ? (
            <p data-testid="not-found">이 값으로 찾을 수 있는 거래가 없습니다.</p>
          ) : (
            <ul>
              {(search.data.references ?? []).map((reference) => (
                <li key={`${reference.kind}-${reference.referenceId}`}>
                  <button
                    type="button"
                    data-testid="reference"
                    onClick={() => setSelected(reference.referenceId ?? null)}
                  >
                    {KIND_LABEL[reference.kind ?? ""] ?? reference.kind} {reference.referenceId}
                  </button>
                  {reference.summary !== undefined && reference.summary !== null && (
                    <small> {reference.summary}</small>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {timeline.data !== undefined && (
        <table data-testid="timeline">
          <thead>
            <tr>
              <th>시각</th>
              <th>종류</th>
              <th>상태</th>
              <th>금액</th>
              <th>내용</th>
            </tr>
          </thead>
          <tbody>
            {(timeline.data.entries ?? []).map((entry, index) => (
              <tr key={`${entry.kind}-${entry.id}-${index}`} data-testid="timeline-row">
                <td>{entry.occurredAt === undefined ? "" : formatInstant(entry.occurredAt)}</td>
                <td>{entry.kind}</td>
                <td>{entry.status}</td>
                <td>{entry.amount === undefined || entry.amount === null ? "" : formatWon(entry.amount)}</td>
                <td>{entry.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
