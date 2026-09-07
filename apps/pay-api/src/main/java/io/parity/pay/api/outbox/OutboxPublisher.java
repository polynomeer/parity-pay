package io.parity.pay.api.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 발행기.
 *
 * <p>커밋된 이벤트를 브로커로 옮깁니다. 발행 도중 프로세스가 죽어도 이벤트는 여전히 PENDING으로
 * 남아 있으므로, 재시작한 발행기가 이어서 발행합니다(F-003).
 *
 * <p>브로커 ACK가 유실되면 같은 이벤트가 두 번 발행될 수 있습니다. 이것은 버그가 아니라 전제이며,
 * 중복은 소비자가 흡수합니다. 근거: ADR-005, ADR-006
 *
 * <p>트랜잭션 경계는 {@link OutboxPublishTransaction}에 있습니다. 같은 빈 안에서 호출하면 프록시를
 * 거치지 않아 트랜잭션이 열리지 않기 때문입니다.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxPublishTransaction publishTransaction;
    private final OutboxProperties properties;

    OutboxPublisher(OutboxPublishTransaction publishTransaction, OutboxProperties properties) {
        this.publishTransaction = publishTransaction;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${paritypay.events.poll-interval-ms:500}")
    void publishScheduled() {
        if (!properties.publisherEnabled()) {
            return;
        }
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.error("outbox publishing round failed", e);
        }
    }

    /** 한 배치를 발행하고 발행한 건수를 돌려줍니다. */
    public int publishBatch() {
        return publishTransaction.publishBatch();
    }
}
