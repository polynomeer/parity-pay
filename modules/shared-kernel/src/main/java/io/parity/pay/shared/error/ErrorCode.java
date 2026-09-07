package io.parity.pay.shared.error;

/**
 * API 오류 코드와 HTTP 상태.
 *
 * <p>여기 없는 코드를 새로 만들지 않습니다. 새 코드가 필요하면 docs/04-payment-policy.md §10과
 * docs/08-db-api-event-spec.md §5를 먼저 갱신합니다.
 */
public enum ErrorCode {
    /** 입력 금액이 정책 범위를 벗어났습니다. */
    INVALID_AMOUNT(400),
    /** 요청 형식이 올바르지 않습니다. */
    INVALID_REQUEST(400),
    /** 지갑이 신규 거래를 허용하는 상태가 아닙니다. */
    WALLET_NOT_ACTIVE(409),
    /** 가용 잔액이 부족합니다. 현재 잔액을 응답에 그대로 노출하지 않습니다. */
    INSUFFICIENT_BALANCE(409),
    /** 같은 멱등 키에 다른 본문이 도착했습니다. */
    IDEMPOTENCY_KEY_REUSED(409),
    /** 현재 상태에서 허용되지 않는 전이입니다. */
    INVALID_STATE_TRANSITION(409),
    /** 누적 취소액이 승인액을 초과합니다. */
    CANCELLATION_AMOUNT_EXCEEDED(409),
    /** 한도를 초과했습니다. */
    LIMIT_EXCEEDED(422),
    /** 위험 규칙으로 차단했습니다. 탐지 규칙 상세를 노출하지 않습니다. */
    RISK_BLOCKED(403),
    /** 리소스를 찾을 수 없거나 접근 권한이 없습니다. */
    RESOURCE_NOT_FOUND(404),
    /** 외부 결과가 아직 확정되지 않았습니다. 실패가 아닙니다. */
    RESULT_PENDING(202),
    /** 외부기관 일시 오류입니다. 재시도 대상입니다. */
    EXTERNAL_TEMPORARY_ERROR(503),
    /** 내부 오류입니다. 내부 정보를 응답에 노출하지 않습니다. */
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
