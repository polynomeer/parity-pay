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
        // 기관이 "이 지급 요청은 더 이상 기록되지 않는다"고 보장하는 창입니다. `null`이면 확정하지 않고
        // 사람에게 넘깁니다. 근거: ADR-016, reports/11 M-030
        Duration recoveryNotFoundSettleAfter,
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
        // 음수는 "기관이 창을 선언하지 않았다"는 뜻입니다. 설정에서 비우는 것과 같고, 설정으로
        // 표현할 수 있어야 시험할 수 있습니다.
        recoveryNotFoundSettleAfter = recoveryNotFoundSettleAfter != null && recoveryNotFoundSettleAfter.isNegative()
                ? null
                : recoveryNotFoundSettleAfter;
        recoveryMaxRoundsPerTick = recoveryMaxRoundsPerTick <= 0 ? 20 : recoveryMaxRoundsPerTick;
    }
}
