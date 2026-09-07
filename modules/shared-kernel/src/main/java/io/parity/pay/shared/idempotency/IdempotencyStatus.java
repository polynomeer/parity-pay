package io.parity.pay.shared.idempotency;

/**
 * 멱등 요청의 수명주기. 근거: docs/09-consistency-recovery.md §3
 *
 * <p>{@code RECOVERY_REQUIRED}는 외부 결과를 모르는 상태입니다. 실패가 아닙니다.
 */
public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    RECOVERY_REQUIRED
}
