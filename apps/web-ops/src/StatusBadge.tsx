/**
 * 서버 상태 열거형을 색으로 옮깁니다. 색 기준은 DOC-15 §4.1입니다.
 *
 * 정상은 초록, 확인 필요는 주황, 즉시 대응은 빨강, **미확정은 보라**입니다. UNKNOWN을 빨강으로
 * 칠하지 않습니다 — 실패가 아니고, 복구가 확정할 때까지 기다리는 상태입니다(FE-002).
 * 문구는 서버 값을 그대로 둡니다. 운영자는 열거형을 읽어야 하는 사람입니다.
 */
const TONE: Record<string, string> = {
  SUCCEEDED: "badge--ok",
  APPROVED: "badge--ok",
  COMPLETED: "badge--ok",
  POSTED: "badge--ok",
  PAID: "badge--ok",
  CONFIRMED: "badge--ok",
  RESOLVED: "badge--ok",
  MATCHED: "badge--ok",
  PUBLISHED: "badge--ok",
  FAILED: "badge--danger",
  REJECTED: "badge--danger",
  UNKNOWN: "badge--unknown",
  PROCESSING: "badge--unknown",
  PAYING: "badge--unknown",
  PAYMENT_UNKNOWN: "badge--unknown",
  REQUESTED: "badge--unknown",
  PENDING: "badge--unknown",
  PARTIALLY_CANCELED: "badge--warn",
  CANCELED: "badge--warn",
  HELD: "badge--warn",
  OPEN: "badge--warn",
  ADJUSTMENT_REQUESTED: "badge--warn",
  REVERSED: "badge--warn",
};

export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (status === undefined || status === null || status === "") {
    return null;
  }
  return <span className={`badge ${TONE[status] ?? ""}`}>{status}</span>;
}
