package io.parity.pay.reconciliation.application.port.out;

/**
 * 대사 대상 기록을 가져오지 못했을 때 던집니다.
 *
 * <p>"기관에 기록이 없다"와 "기관에 물어보지 못했다"는 다릅니다. 두 경우를 모두 빈 목록으로 만들면
 * 명세를 받지 못한 날에 우리 쪽 기록 전부가 불일치로 올라가고, 운영자는 존재하지 않는 문제를 놓고
 * 보정 분개를 검토하게 됩니다.
 *
 * <p>그래서 실패는 값이 아니라 예외입니다. 대사는 이 예외를 받으면 아무것도 기록하지 않고 멈춥니다.
 *
 * <p>근거: ADR-007(모르는 것을 실패로 단정하지 않음), docs/04-payment-policy.md §9
 */
public class ReconciliationSourceUnavailableException extends RuntimeException {

    public ReconciliationSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
