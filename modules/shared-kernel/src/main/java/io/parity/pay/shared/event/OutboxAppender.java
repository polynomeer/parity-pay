package io.parity.pay.shared.event;

/**
 * 이벤트를 Outbox에 기록하는 포트.
 *
 * <p>업무 상태 변경과 같은 트랜잭션에서 호출합니다. 커밋 후 애플리케이션 코드가 브로커로 직접
 * 발행하지 않습니다. 커밋과 발행 사이의 유실 창을 없애기 위한 설계입니다. 근거: ADR-005
 *
 * <p>구현은 트랜잭션 밖 호출을 거부해야 합니다.
 */
public interface OutboxAppender {

    void append(EventEnvelope envelope);
}
