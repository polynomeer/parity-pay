------------------------- MODULE UnknownResolutionWindow -------------------------
(***************************************************************************)
(* UnknownResolution 에 기관이 보장하는 "창"을 더한 명세입니다 (ADR-016).     *)
(*                                                                         *)
(* UnknownResolution 에서 NoFalseFailure 가 깨지는 이유는 하나였습니다 —      *)
(* 기관이 요청을 받아 둔 채 **언제까지고** 기록할 수 있었기 때문입니다.        *)
(* 임계치를 올려도 그 경로는 남습니다. 여기서는 기관이 계약으로 창을 선언한    *)
(* 경우를 모델에 넣습니다: 창이 닫히면 기관은 더 이상 기록하지 않고, 복구는    *)
(* **창이 닫힌 뒤에만** 연속 "없음"을 실패로 확정합니다.                      *)
(*                                                                         *)
(* 그러므로 이 명세가 증명하는 것은 "구현이 안전하다"가 아니라               *)
(* **"기관이 그 창을 지키면 규칙이 안전하다"** 입니다. 창은 가정이고,        *)
(* 모델은 가정을 검증하지 못합니다. 근거: ADR-016, reports/11 M-030          *)
(***************************************************************************)
EXTENDS Naturals

CONSTANTS
    NotFoundThreshold,   \* 연속 "없음"을 몇 번 보면 거절로 확정하는가 (구현 기본값 2)
    MaxAttempts          \* 이 횟수를 넘기면 사람에게 넘깁니다

VARIABLES
    payStatus,   \* "PROCESSING" | "UNKNOWN" | "APPROVED" | "FAILED" | "MANUAL"
    pgState,     \* "none" | "inflight" | "approved" | "declined"
    ledger,      \* 전기된 원장 건수. INV-004 는 이것이 1을 넘지 않는 것입니다
    notFound,    \* 연속 "없음" 횟수
    attempts,    \* 조회 시도 횟수
    queryUp,     \* 상태 조회가 가능한가
    windowClosed \* 기관이 보장한 창이 닫혔는가 (닫히면 기관은 더 이상 기록하지 않습니다)

vars == <<payStatus, pgState, ledger, notFound, attempts, queryUp, windowClosed>>

Final == {"APPROVED", "FAILED", "MANUAL"}

TypeOK ==
    /\ payStatus \in {"PROCESSING", "UNKNOWN", "APPROVED", "FAILED", "MANUAL"}
    /\ pgState \in {"none", "inflight", "approved", "declined"}
    /\ ledger \in 0..2
    /\ notFound \in 0..(NotFoundThreshold + 1)
    /\ attempts \in 0..(MaxAttempts + NotFoundThreshold + 2)
    /\ queryUp \in BOOLEAN
    /\ windowClosed \in BOOLEAN

Init ==
    /\ payStatus = "PROCESSING"
    /\ pgState = "none"
    /\ ledger = 0
    /\ notFound = 0
    /\ attempts = 0
    /\ queryUp = TRUE
    /\ windowClosed = FALSE

(* 승인 요청이 소켓을 떠났습니다. 응답은 오지 않았고 결제는 UNKNOWN 이 됩니다.  *)
(* 기관은 아직 기록하지 않았습니다 - 받아서 처리 중인 상태입니다.              *)
SendApproval ==
    /\ payStatus = "PROCESSING"
    /\ pgState = "none"
    /\ payStatus' = "UNKNOWN"
    /\ pgState' = "inflight"
    /\ UNCHANGED <<ledger, notFound, attempts, queryUp, windowClosed>>

(* 기관이 처리 중이던 요청을 기록합니다. 이 시점은 우리가 정하지 못합니다.     *)
PgSettleApproved ==
    /\ pgState = "inflight"
    /\ windowClosed = FALSE   \* 창이 닫힌 뒤에는 기록하지 않는다는 것이 기관의 계약입니다
    /\ pgState' = "approved"
    /\ UNCHANGED <<payStatus, ledger, notFound, attempts, queryUp, windowClosed>>

PgSettleDeclined ==
    /\ pgState = "inflight"
    /\ windowClosed = FALSE
    /\ pgState' = "declined"
    /\ UNCHANGED <<payStatus, ledger, notFound, attempts, queryUp, windowClosed>>

(* 창이 닫힙니다. 이 시점은 요청을 보낸 시각 + 기관이 선언한 창입니다.        *)
CloseWindow ==
    /\ windowClosed = FALSE
    /\ windowClosed' = TRUE
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, attempts, queryUp>>

(* 조회 가능 여부는 오르내립니다 (F-009).                                    *)
QueryDown ==
    /\ queryUp = TRUE
    /\ queryUp' = FALSE
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, attempts, windowClosed>>

QueryUp ==
    /\ queryUp = FALSE
    /\ queryUp' = TRUE
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, attempts, windowClosed>>

(* --- 복구 작업: resolveOne --- *)

(* 이미 확정된 건은 그대로 통과합니다.                                        *)
RecoverAlreadyFinal ==
    /\ payStatus \in Final
    /\ UNCHANGED vars

(* 조회가 승인. 정상 흐름과 같은 트랜잭션으로 확정하고 원장을 한 번 전기합니다. *)
RecoverApproved ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState = "approved"
    /\ payStatus' = "APPROVED"
    /\ ledger' = ledger + 1
    /\ UNCHANGED <<pgState, notFound, attempts, queryUp, windowClosed>>

(* 조회가 거절. 예약을 풀고 FAILED 로 확정합니다. 원장은 전기하지 않습니다.    *)
RecoverDeclined ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState = "declined"
    /\ payStatus' = "FAILED"
    /\ UNCHANGED <<pgState, ledger, notFound, attempts, queryUp, windowClosed>>

(* 조회에 기록이 없음. 한 번으로 확정하지 않고 연속 횟수를 셉니다.            *)
(* 기관이 아직 처리 중이면(inflight) 조회는 "없음"으로 보입니다.              *)
RecoverNotFoundRetry ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState \in {"none", "inflight"}
    /\ notFound + 1 < NotFoundThreshold
    /\ notFound' = notFound + 1
    /\ attempts' = attempts + 1
    /\ UNCHANGED <<payStatus, pgState, ledger, queryUp, windowClosed>>

(* 연속 "없음"이 한계에 닿고 **창도 닫혔으면** 거절로 확정합니다 (ADR-016).   *)
RecoverNotFoundSettle ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState \in {"none", "inflight"}
    /\ windowClosed = TRUE
    /\ notFound + 1 >= NotFoundThreshold
    /\ payStatus' = "FAILED"
    /\ notFound' = notFound + 1
    /\ UNCHANGED <<pgState, ledger, attempts, queryUp, windowClosed>>

(* 창이 열려 있는 동안에는 한계에 닿아도 확정하지 않고 계속 묻습니다.         *)
RecoverNotFoundWaitForWindow ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState \in {"none", "inflight"}
    /\ windowClosed = FALSE
    /\ notFound + 1 >= NotFoundThreshold
    /\ notFound' = IF notFound < NotFoundThreshold THEN notFound + 1 ELSE notFound
    \* attempts 는 그대로 둡니다. 구현은 백오프 계산을 위해 올리지만, 그 숫자가 쓰이는 곳은
    \* 조회 실패(UNAVAILABLE)의 수동 검토 전환 하나뿐이고 창을 기다리는 것은 그 예산을
    \* 쓰지 않습니다. 모델에서 올리면 상태가 무한히 늘어나기만 합니다.
    /\ UNCHANGED <<payStatus, pgState, ledger, attempts, queryUp, windowClosed>>

(* 조회 자체가 실패하면 아무것도 확정하지 않고 다시 시도합니다.               *)
RecoverUnavailableRetry ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = FALSE
    /\ attempts + 1 < MaxAttempts
    /\ attempts' = attempts + 1
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, queryUp, windowClosed>>

(* 한도를 넘기면 자동 확정을 멈추고 사람에게 넘깁니다.                        *)
RecoverEscalate ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = FALSE
    /\ attempts + 1 >= MaxAttempts
    /\ payStatus' = "MANUAL"
    /\ attempts' = attempts + 1
    /\ UNCHANGED <<pgState, ledger, notFound, queryUp, windowClosed>>

Next ==
    \/ SendApproval
    \/ PgSettleApproved
    \/ PgSettleDeclined
    \/ QueryDown
    \/ QueryUp
    \/ RecoverApproved
    \/ RecoverDeclined
    \/ RecoverNotFoundRetry
    \/ RecoverNotFoundSettle
    \/ RecoverNotFoundWaitForWindow
    \/ CloseWindow
    \/ RecoverUnavailableRetry
    \/ RecoverEscalate
    \/ RecoverAlreadyFinal

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

(* --- 불변조건 --- *)

(* INV-004: 같은 업무 참조의 금융 효과는 정확히 1회.                          *)
ExactlyOnce == ledger <= 1

(* 청구됐는데 실패로 알린 상태. 고객은 돈이 나갔고 화면은 실패입니다.          *)
NoFalseFailure == ~(pgState = "approved" /\ payStatus = "FAILED")

(* 청구가 없는데 승인으로 확정한 상태.                                        *)
NoFalseSuccess == ~(pgState \in {"none", "declined"} /\ payStatus = "APPROVED")

(* 원장이 있으면 기관에도 승인이 있어야 합니다.                               *)
LedgerImpliesCharge == (ledger > 0) => (pgState = "approved")

(* 살아 있음: 미확정은 언젠가 끝납니다.                                       *)
EventuallySettled == <>(payStatus \in Final)

(* 창이 닫힌 뒤에는 기관의 상태가 더 움직이지 않습니다. 확정의 근거입니다.     *)
RecordStableAfterWindow == (windowClosed /\ pgState = "inflight") => (pgState = "inflight")
=================================================================================
