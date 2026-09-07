package io.parity.pay.shared.error;

import java.util.Map;
import java.util.Objects;

/**
 * 업무 규칙 위반 예외. 스택트레이스를 사용자 응답에 노출하지 않습니다.
 *
 * <p>{@code details}에는 사용자가 조치할 수 있는 정보만 담습니다. 잔액, 내부 식별자, 탐지 규칙 등
 * 노출하면 안 되는 값을 넣지 않습니다. 근거: docs/04-payment-policy.md §10
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
