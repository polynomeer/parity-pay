/**
 * 표시 규칙 (FE-007).
 *
 * 금액은 KRW 원 단위 정수입니다. 계산은 하지 않고 **표시할 때만** 포맷합니다. 부동소수점 연산이
 * 들어가는 순간 원 단위가 깨집니다.
 */
const won = new Intl.NumberFormat("ko-KR");
const dateTime = new Intl.DateTimeFormat("ko-KR", {
  dateStyle: "medium",
  timeStyle: "short",
});

export function formatWon(amount: number): string {
  return `${won.format(amount)}원`;
}

/** 서버는 UTC로 보냅니다. 표시는 사용자 시간대로 합니다. 근거: docs/04-payment-policy.md §1 */
export function formatInstant(iso: string): string {
  return dateTime.format(new Date(iso));
}
