package io.parity.pay.api.outbox;

import java.util.List;
import java.util.UUID;

/**
 * 브로커 전송 포트.
 *
 * <p>한 건씩이 아니라 배치로 보냅니다. 건별로 보내고 ACK를 기다리면 왕복 지연이 그대로 처리량
 * 상한이 됩니다(실측 80건/초, reports/11 P-003). 배치로 보내되 **확인 시점은 그대로 둡니다.**
 * 브로커가 받았다고 답한 건만 발행됨으로 표시합니다. 확인 전에 표시하면 유실 창이 생깁니다.
 *
 * <p>전송은 at-least-once로 가정합니다. ACK가 유실되면 같은 이벤트가 다시 발행될 수 있고, 그것을
 * 정상 운영 조건으로 취급합니다. 근거: ADR-005, ADR-006
 */
interface MessageBroker {

    /**
     * 여러 이벤트를 보내고 각각의 결과를 돌려줍니다.
     *
     * <p>일부만 실패할 수 있으므로 결과는 이벤트 단위입니다. 하나가 실패했다고 성공한 것까지
     * 재시도하면 중복 발행이 늘어납니다.
     */
    List<SendOutcome> sendAll(String topic, List<OutboxMessage> messages);

    record OutboxMessage(UUID eventId, String partitionKey, String payload) {}

    record SendOutcome(UUID eventId, boolean acknowledged, String failureMessage) {

        static SendOutcome acknowledged(UUID eventId) {
            return new SendOutcome(eventId, true, null);
        }

        static SendOutcome failed(UUID eventId, String failureMessage) {
            return new SendOutcome(eventId, false, failureMessage);
        }
    }
}
