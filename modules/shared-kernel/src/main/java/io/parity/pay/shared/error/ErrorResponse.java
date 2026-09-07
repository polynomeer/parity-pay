package io.parity.pay.shared.error;

import java.util.Map;

/**
 * 표준 오류 응답 본문.
 *
 * <p>근거: docs/08-db-api-event-spec.md §5
 */
public record ErrorResponse(String code, String message, String traceId, Map<String, Object> details) {

    public static ErrorResponse of(ErrorCode code, String message, String traceId, Map<String, Object> details) {
        return new ErrorResponse(code.name(), message, traceId, details == null ? Map.of() : Map.copyOf(details));
    }
}
