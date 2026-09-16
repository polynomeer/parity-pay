package io.parity.pay.api.outbox;

import io.parity.pay.api.eventing.DeadLetterRecoverer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * 이벤트 인프라 설정.
 *
 * <p>토픽은 하나이고 파티션 키로 Aggregate 순서를 보장합니다. 순서가 필요한 단위는 Aggregate이지
 * 토픽 전체가 아닙니다. 근거: docs/05-technical-design.md §9
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class EventingConfig {

    @Bean
    NewTopic parityPayEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }

    /** 소비자가 끝내 처리하지 못한 레코드가 가는 곳입니다. 결함 M(reports/11 M-018) 이후 생겼습니다. */
    @Bean
    NewTopic parityPayEventsDeadLetterTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic() + DeadLetterRecoverer.DLT_SUFFIX)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 소비자 오류 처리.
     *
     * <p>spring-kafka 기본값은 0 ms 간격으로 10회 재시도한 뒤 ERROR 로그만 남기고 건너뛰는 것이었습니다.
     * 그 경로를 우리 이벤트가 타면 업무 효과가 조용히 빠집니다(결함 M). 지금은:
     *
     * <ul>
     *   <li>일시적 오류(DB·네트워크)는 1초 간격으로 두 번 더 시도합니다. 0 ms 재시도는 같은 순간에
     *       같은 실패를 반복할 뿐입니다.
     *   <li>파싱 실패({@link JacksonException})는 재시도하지 않습니다. 같은 바이트는 몇 번 읽어도
     *       같은 결과이고, 재시도는 그 파티션을 그만큼 더 멈춰 세울 뿐입니다.
     *   <li>포기한 레코드는 {@link DeadLetterRecoverer}가 DB와 DLT에 남긴 뒤에야 오프셋을 넘깁니다.
     * </ul>
     *
     * <p>Boot는 이 빈을 기본 리스너 컨테이너 팩토리에 붙입니다. 두 소비자(거래내역 프로젝션·정산
     * 항목)가 같은 규칙을 씁니다.
     */
    @Bean
    CommonErrorHandler kafkaListenerErrorHandler(DeadLetterRecoverer recoverer) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
