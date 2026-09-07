package io.parity.pay.api.outbox;

/**
 * 브로커 전송 포트.
 *
 * <p>전송은 at-least-once로 가정합니다. ACK가 유실되면 같은 이벤트가 다시 발행될 수 있고, 그것을
 * 정상 운영 조건으로 취급합니다. 근거: ADR-006
 */
interface MessageBroker {

    /** 전송이 확인될 때까지 기다립니다. 실패하면 예외를 던져 재시도 대상이 되게 합니다. */
    void send(String topic, String partitionKey, String payload);
}
