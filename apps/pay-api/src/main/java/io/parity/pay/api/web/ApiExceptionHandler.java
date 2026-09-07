package io.parity.pay.api.web;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.error.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 표준 오류 응답으로 변환합니다.
 *
 * <p>스택트레이스와 내부 메시지를 사용자에게 노출하지 않고 {@code traceId}만 제공합니다.
 * 근거: docs/04-payment-policy.md §10, docs/08-db-api-event-spec.md §5
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final MeterRegistry meterRegistry;

    ApiExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 거절 사유별 건수를 셉니다. 어떤 규칙이 실제로 트래픽을 막고 있는지 알아야 정책을 조정할 수
     * 있습니다. 근거: docs/02-prd.md §7
     */
    private void countRejection(ErrorCode code) {
        meterRegistry.counter("paritypay.api.rejections", "code", code.name()).increment();
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("business rule failure: {}", code, exception);
        } else {
            log.info("business rule rejected request: {} {}", code, exception.getMessage());
        }
        countRejection(code);
        ErrorResponse body =
                ErrorResponse.of(code, exception.getMessage(), traceId(), exception.details());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception) {
        countRejection(ErrorCode.INVALID_REQUEST);
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request is not valid");
        ErrorResponse body =
                ErrorResponse.of(ErrorCode.INVALID_REQUEST, message, traceId(), java.util.Map.of());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus()).body(body);
    }

    /**
     * 존재하지 않는 경로입니다.
     *
     * <p>이것을 500으로 응답하면 "서버가 고장났다"는 신호를 잘못 보냅니다. 경보와 오류율 지표가
     * 오염되므로 404로 구분합니다.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException exception) {
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.RESOURCE_NOT_FOUND, "the requested resource does not exist", traceId(),
                java.util.Map.of());
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.httpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        countRejection(ErrorCode.INTERNAL_ERROR);
        log.error("unexpected failure", exception);
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.INTERNAL_ERROR,
                "요청을 처리하지 못했습니다. 문제가 계속되면 traceId와 함께 문의해 주세요.",
                traceId(),
                java.util.Map.of());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus()).body(body);
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
