package io.parity.pay.api.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Outbox 적체 지표.
 *
 * <p>미발행 건수만으로는 문제를 알 수 없습니다. 발행기가 멈추면 건수는 천천히 늘지만 가장 오래된
 * 이벤트의 나이는 즉시 자랍니다. 근거: docs/05-technical-design.md §12
 *
 * <p>소비 지연(consumer lag)은 브로커가 알고 있는 값이므로 spring-kafka가 노출하는
 * {@code kafka.consumer.*} 메트릭을 사용합니다.
 */
@Component
class OutboxMetrics {

    private final OutboxRepository outboxRepository;
    private final Clock clock;

    OutboxMetrics(OutboxRepository outboxRepository, MeterRegistry meterRegistry, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.clock = clock;

        Gauge.builder("paritypay.outbox.pending", this, self -> self.outboxRepository.countByStatus("PENDING"))
                .description("아직 발행되지 않은 Outbox 이벤트 수")
                .register(meterRegistry);

        Gauge.builder("paritypay.outbox.failed", this, self -> self.outboxRepository.countByStatus("FAILED"))
                .description("최대 재시도를 넘겨 운영자 확인이 필요한 이벤트 수")
                .register(meterRegistry);

        // 전체 적체가 작아도 한 파티션 키만 계속 밀릴 수 있습니다. 발행기가 키마다 선두 하나만
        // 집어가므로 그 키의 발행은 직렬화되고, 기본 설정에서 초당 26.6건이 상한입니다(M-003).
        // 이 값이 pending에 근접하면 적체가 한 곳에 몰려 있다는 뜻입니다.
        Gauge.builder(
                        "paritypay.outbox.max_partition_pending",
                        this,
                        self -> self.outboxRepository.maxPartitionPending())
                .description("한 파티션 키에 몰려 있는 미발행 이벤트 수의 최댓값")
                .register(meterRegistry);

        Gauge.builder(
                        "paritypay.outbox.oldest_pending_age_seconds",
                        this,
                        self -> self.outboxRepository.oldestPendingAgeSeconds(self.clock.instant()))
                .description("가장 오래된 미발행 이벤트의 나이(초)")
                .register(meterRegistry);
    }
}
