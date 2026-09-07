package io.parity.pay.api.outbox;

/**
 * 이벤트가 자기 스키마를 만족하지 않습니다.
 *
 * <p>소비자와의 계약을 깨는 변경이므로 이벤트를 기록하지 않고 업무 트랜잭션을 되돌립니다.
 * 근거: docs/08-db-api-event-spec.md §8
 */
class EventContractViolationException extends RuntimeException {

    EventContractViolationException(String message) {
        super(message);
    }
}
