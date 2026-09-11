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
        int recoveryNotFoundConfirmThreshold,
        // 한 틱에 이어서 처리할 배치 수입니다. 틱당 한 배치면 상한이 초당 10건으로 고정됩니다(M-012).
        int recoveryMaxRoundsPerTick) {

    public SettlementProperties {
        feeBasisPoints = feeBasisPoints <= 0 ? 1_000 : feeBasisPoints;
        maxItemsPerSettlement = maxItemsPerSettlement <= 0 ? 1_000 : maxItemsPerSettlement;
        recoveryLease = recoveryLease == null ? Duration.ofSeconds(30) : recoveryLease;
        recoveryBaseBackoff = recoveryBaseBackoff == null ? Duration.ofSeconds(2) : recoveryBaseBackoff;
        recoveryMaxBackoff = recoveryMaxBackoff == null ? Duration.ofMinutes(10) : recoveryMaxBackoff;
        recoveryMaxAttempts = recoveryMaxAttempts <= 0 ? 8 : recoveryMaxAttempts;
        recoveryNotFoundConfirmThreshold = recoveryNotFoundConfirmThreshold <= 0 ? 3 : recoveryNotFoundConfirmThreshold;
        recoveryMaxRoundsPerTick = recoveryMaxRoundsPerTick <= 0 ? 20 : recoveryMaxRoundsPerTick;
    }
}
