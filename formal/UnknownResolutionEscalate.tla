---------------------------- MODULE UnknownResolutionEscalate ----------------------------
(***************************************************************************)
(* PaymentRecoveryService.resolveOne 의 규칙을 그대로 옮긴 명세입니다.        *)
(*                                                                         *)
(* 실험 41종은 시각이 맞아떨어지는 순간에만 드러나는 경로를 우연에 기대서만    *)
(* 밟습니다. 모델 검사는 순서를 전부 밟습니다. 이 명세가 묻는 것은 하나 —      *)
(* 구현된 규칙 아래에서 "청구됐는데 실패로 확정되는" 상태에 도달할 수 있는가.  *)
(*                                                                         *)
(* 근거: docs/09-consistency-recovery.md §7·§8, ADR-007                     *)
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
    queryUp      \* 상태 조회가 가능한가

vars == <<payStatus, pgState, ledger, notFound, attempts, queryUp>>

Final == {"APPROVED", "FAILED", "MANUAL"}

TypeOK ==
    /\ payStatus \in {"PROCESSING", "UNKNOWN", "APPROVED", "FAILED", "MANUAL"}
    /\ pgState \in {"none", "inflight", "approved", "declined"}
    /\ ledger \in 0..2
    /\ notFound \in 0..(NotFoundThreshold + 1)
    /\ attempts \in 0..(MaxAttempts + NotFoundThreshold + 2)
    /\ queryUp \in BOOLEAN

Init ==
    /\ payStatus = "PROCESSING"
    /\ pgState = "none"
    /\ ledger = 0
    /\ notFound = 0
    /\ attempts = 0
    /\ queryUp = TRUE

(* 승인 요청이 소켓을 떠났습니다. 응답은 오지 않았고 결제는 UNKNOWN 이 됩니다.  *)
(* 기관은 아직 기록하지 않았습니다 - 받아서 처리 중인 상태입니다.              *)
SendApproval ==
    /\ payStatus = "PROCESSING"
    /\ pgState = "none"
    /\ payStatus' = "UNKNOWN"
    /\ pgState' = "inflight"
    /\ UNCHANGED <<ledger, notFound, attempts, queryUp>>

(* 기관이 처리 중이던 요청을 기록합니다. 이 시점은 우리가 정하지 못합니다.     *)
PgSettleApproved ==
    /\ pgState = "inflight"
    /\ pgState' = "approved"
    /\ UNCHANGED <<payStatus, ledger, notFound, attempts, queryUp>>

PgSettleDeclined ==
    /\ pgState = "inflight"
    /\ pgState' = "declined"
    /\ UNCHANGED <<payStatus, ledger, notFound, attempts, queryUp>>

(* 조회 가능 여부는 오르내립니다 (F-009).                                    *)
QueryDown ==
    /\ queryUp = TRUE
    /\ queryUp' = FALSE
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, attempts>>

QueryUp ==
    /\ queryUp = FALSE
    /\ queryUp' = TRUE
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, attempts>>

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
    /\ UNCHANGED <<pgState, notFound, attempts, queryUp>>

(* 조회가 거절. 예약을 풀고 FAILED 로 확정합니다. 원장은 전기하지 않습니다.    *)
RecoverDeclined ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState = "declined"
    /\ payStatus' = "FAILED"
    /\ UNCHANGED <<pgState, ledger, notFound, attempts, queryUp>>

(* 조회에 기록이 없음. 한 번으로 확정하지 않고 연속 횟수를 셉니다.            *)
(* 기관이 아직 처리 중이면(inflight) 조회는 "없음"으로 보입니다.              *)
RecoverNotFoundRetry ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState \in {"none", "inflight"}
    /\ notFound + 1 < NotFoundThreshold
    /\ notFound' = notFound + 1
    /\ attempts' = attempts + 1
    /\ UNCHANGED <<payStatus, pgState, ledger, queryUp>>

(* 처치 갈래: 연속 "없음"을 거절로 확정하지 않고 사람에게 넘깁니다.           *)
(* 나머지는 한 글자도 바꾸지 않았습니다. 이 한 전이가 유일한 차이입니다.      *)
RecoverNotFoundSettle ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = TRUE
    /\ pgState \in {"none", "inflight"}
    /\ notFound + 1 >= NotFoundThreshold
    /\ payStatus' = "MANUAL"
    /\ notFound' = notFound + 1
    /\ UNCHANGED <<pgState, ledger, attempts, queryUp>>

(* 조회 자체가 실패하면 아무것도 확정하지 않고 다시 시도합니다.               *)
RecoverUnavailableRetry ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = FALSE
    /\ attempts + 1 < MaxAttempts
    /\ attempts' = attempts + 1
    /\ UNCHANGED <<payStatus, pgState, ledger, notFound, queryUp>>

(* 한도를 넘기면 자동 확정을 멈추고 사람에게 넘깁니다.                        *)
RecoverEscalate ==
    /\ payStatus = "UNKNOWN"
    /\ queryUp = FALSE
    /\ attempts + 1 >= MaxAttempts
    /\ payStatus' = "MANUAL"
    /\ attempts' = attempts + 1
    /\ UNCHANGED <<pgState, ledger, notFound, queryUp>>

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
=================================================================================
