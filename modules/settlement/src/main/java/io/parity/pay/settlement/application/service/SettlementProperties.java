package io.parity.pay.settlement.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 정산 설정.
 *
 * <p>수수료율은 basis point(만분율) 정수입니다. 비율 계산에 부동소수점을 쓰지 않기 위해서입니다.
 * 근거: BR-001
 */
@ConfigurationProperties(prefix = "paritypay.settlement")
public record SettlementProperties(
        int feeBasisPoints,
        int maxItemsPerSettlement,
        boolean recoveryEnabled,
        Duration recoveryLease,
        Duration recoveryBaseBackoff,
        Duration recoveryMaxBackoff,
        int recoveryMaxAttempts,
        int recoveryNotFoundConfirmThreshold) {

    public SettlementProperties {
        feeBasisPoints = feeBasisPoints <= 0 ? 1_000 : feeBasisPoints;
        maxItemsPerSettlement = maxItemsPerSettlement <= 0 ? 1_000 : maxItemsPerSettlement;
        recoveryLease = recoveryLease == null ? Duration.ofSeconds(30) : recoveryLease;
        recoveryBaseBackoff = recoveryBaseBackoff == null ? Duration.ofSeconds(2) : recoveryBaseBackoff;
        recoveryMaxBackoff = recoveryMaxBackoff == null ? Duration.ofMinutes(10) : recoveryMaxBackoff;
        recoveryMaxAttempts = recoveryMaxAttempts <= 0 ? 8 : recoveryMaxAttempts;
        recoveryNotFoundConfirmThreshold = recoveryNotFoundConfirmThreshold <= 0 ? 3 : recoveryNotFoundConfirmThreshold;
    }
}
