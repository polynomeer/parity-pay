package io.parity.pay.shared.event;

import io.parity.pay.shared.id.EventId;

/**
 * 소비 이력 저장소 포트.
 *
 * <p>at-least-once 전달에서 같은 이벤트는 여러 번 도착합니다. 소비자는 업무 처리와 소비 기록을 같은
 * 로컬 트랜잭션에 넣어, 재전달이 결과를 다시 만들지 않게 합니다. 근거: ADR-006
 */
public interface ConsumedEventStore {

    /**
     * 이 소비자가 이 이벤트를 처음 처리하는지 표시합니다.
     *
     * @return 처음이면 {@code true}. 이미 처리한 이벤트면 {@code false}이며 호출자는 아무것도 하지
     *     않고 성공으로 끝냅니다.
     */
    boolean markConsumed(String consumerName, EventId eventId);
}
