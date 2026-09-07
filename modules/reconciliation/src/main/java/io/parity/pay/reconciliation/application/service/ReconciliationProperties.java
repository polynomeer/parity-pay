package io.parity.pay.reconciliation.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대사 설정.
 *
 * <p>{@code delayTolerance}는 외부 기록이 늦게 도착하는 것을 정상으로 보는 시간입니다. 이 값이 너무
 * 짧으면 정상 지연이 전부 불일치로 올라와 운영자가 노이즈에 묻힙니다.
 * 근거: docs/04-payment-policy.md §9
 */
@ConfigurationProperties(prefix = "paritypay.reconciliation")
public record ReconciliationProperties(boolean enabled, Duration delayTolerance, Duration window) {

    public ReconciliationProperties {
        delayTolerance = delayTolerance == null ? Duration.ofMinutes(30) : delayTolerance;
        window = window == null ? Duration.ofDays(7) : window;
    }
}
