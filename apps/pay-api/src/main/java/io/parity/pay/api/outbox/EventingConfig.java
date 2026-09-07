package io.parity.pay.api.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

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
}
