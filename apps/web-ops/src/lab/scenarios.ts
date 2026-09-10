/**
 * 장애 시나리오 (DOC-15 §5.1).
 *
 * **여기 있는 것은 전부 기관이 실제로 하는 행동입니다.** 우리가 상태를 손으로 바꾸는 것이
 * 아니라, 기관을 그렇게 행동하게 만들고 우리 시스템이 어떻게 반응하는지 봅니다.
 *
 * DOC-15가 나열한 12개 중 넷은 여기 없습니다. 이유는 DOC-16 §2.3에 있습니다 — 지연 주입은
 * 기관에 기능이 없고, 발행기·소비자 중단은 운영 콘솔에 두면 위험한 스위치이며 프로세스를 죽이는
 * 실험은 `load-tests/`가 이미 합니다. **없는 것을 버튼으로 만들지 않습니다.**
 */
import type { FailureScenario } from "@paritypay/api-client";

export const SCENARIOS: readonly FailureScenario[] = [
  {
    id: "normal",
    label: "정상 처리",
    target: "bank",
    bankMode: "NORMAL",
    explains: "기준선입니다. 다른 시나리오와 비교할 때 씁니다.",
  },
  {
    id: "bank-explicit-failure",
    label: "은행이 명시적으로 실패를 응답",
    target: "bank",
    bankMode: "EXPLICIT_FAILURE",
    explains: "결과를 아는 실패입니다. 충전은 FAILED로 확정되고 잔액은 움직이지 않습니다.",
  },
  {
    id: "bank-timeout-before",
    label: "은행이 출금 전에 응답을 끊음",
    target: "bank",
    bankMode: "TIMEOUT_BEFORE_WITHDRAWAL",
    explains:
      "외부에 기록이 없습니다. 한 번으로 단정하지 않고 연속 확인한 뒤 FAILED가 됩니다. 약 45초 걸립니다(M-011).",
  },
  {
    id: "bank-timeout-after",
    label: "은행이 출금 뒤에 응답을 끊음",
    target: "bank",
    bankMode: "TIMEOUT_AFTER_WITHDRAWAL",
    explains: "돈은 이미 움직였고 응답만 유실됐습니다. 조회로 SUCCEEDED가 됩니다. 약 35초 걸립니다(M-011).",
  },
  {
    id: "pg-timeout-after",
    label: "카드 PG가 승인 뒤에 응답을 끊음",
    target: "pg",
    pgMode: "TIMEOUT_AFTER_APPROVAL",
    explains: "결제가 UNKNOWN으로 보존됩니다. 실패로 덮지 않는 것이 요점입니다(ADR-007).",
  },
  {
    id: "pg-query-down",
    label: "카드 PG 상태 조회 장애",
    target: "pg",
    statusQueryAvailable: false,
    explains: "복구가 확정하지 못합니다. 아무것도 단정하지 않고 백오프하다 수동 검토로 넘어갑니다.",
  },
  {
    id: "webhook-duplicate",
    label: "웹훅 중복 전달",
    target: "pg",
    webhookMode: "DUPLICATE",
    explains: "같은 알림이 두 번 옵니다. webhook_receipt가 두 번째를 막고 금융 효과는 1회입니다.",
  },
  {
    id: "webhook-out-of-order",
    label: "웹훅 역순 도착",
    target: "pg",
    webhookMode: "OUT_OF_ORDER",
    explains: "오래된 알림이 나중에 옵니다. webhook_cursor가 상태 회귀를 막습니다.",
  },
  {
    id: "webhook-none",
    label: "웹훅을 아예 보내지 않음",
    target: "pg",
    webhookMode: "NONE",
    explains: "알림 없이 조회만으로 확정되는지 봅니다. 웹훅은 지름길이지 진실의 원천이 아닙니다.",
  },
];
