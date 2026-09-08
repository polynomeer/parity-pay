package io.parity.pay.api.mockpg;

/**
 * 외부 PG 호출의 결과를 모릅니다.
 *
 * <p>연결 거부·읽기 타임아웃·5xx가 모두 여기로 옵니다. 남는 사실은 하나입니다 — **청구되었는지,
 * 환불되었는지 모른다.** 이것을 실패로 바꾸면 청구된 결제를 실패로 알리거나, 나간 환불을 다시
 * 보내게 됩니다. 근거: ADR-007
 */
class PgUnknownResultException extends RuntimeException {

    PgUnknownResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
