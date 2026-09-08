package io.parity.pay.api.mockbank;

/**
 * 외부 호출의 결과를 모릅니다.
 *
 * <p>연결이 끊겼든, 응답이 오지 않았든, 500이 왔든 마찬가지입니다. **자금이 움직였는지 알 수
 * 없다**는 한 가지 사실만 남습니다. 이것을 실패로 바꾸는 순간 시스템이 거짓말을 시작합니다.
 *
 * <p>호출하는 서비스는 이 예외를 잡아 `UNKNOWN` 상태로 보존합니다. 근거: ADR-007, CLAUDE.md §3
 */
class BankUnknownResultException extends RuntimeException {

    BankUnknownResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
