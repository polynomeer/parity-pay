package io.parity.pay.wallet.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 충전 복구 작업 설정.
 *
 * <p>{@code grace}는 정상 처리 중인 거래를 복구 작업이 가로채지 않도록 두는 여유 시간입니다.
 * 이 시간이 너무 짧으면 아직 응답을 기다리는 요청을 복구 작업이 건드립니다.
 */
@ConfigurationProperties(prefix = "paritypay.recovery.top-up")
public record TopUpRecoveryProperties(
        boolean enabled,
        Duration grace,
        Duration lease,
        Duration baseBackoff,
        Duration maxBackoff,
        int maxAttempts,
        int notFoundConfirmThreshold,
        // 기관이 "이 요청은 더 이상 기록되지 않는다"고 보장하는 창입니다. 연속 "없음"을 실패로
        // 확정하려면 이 창이 지나야 합니다. `null`이면 기관이 창을 선언하지 않은 것이므로 확정하지
        // 않고 사람에게 넘깁니다. 근거: ADR-016, reports/11 M-030
        Duration notFoundSettleAfter,
        int batchSize,
        // 한 틱에 이어서 처리할 배치 수입니다. 틱당 한 배치면 상한이 초당 10건으로 고정됩니다(M-012).
        int maxRoundsPerTick) {

    public TopUpRecoveryProperties {
        grace = grace == null ? Duration.ofSeconds(30) : grace;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(2) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(10) : maxBackoff;
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        notFoundConfirmThreshold = notFoundConfirmThreshold <= 0 ? 3 : notFoundConfirmThreshold;
        // 음수는 "기관이 창을 선언하지 않았다"는 뜻입니다. 설정에서 비우는 것과 같고, 설정으로
        // 표현할 수 있어야 시험할 수 있습니다.
        notFoundSettleAfter =
                notFoundSettleAfter != null && notFoundSettleAfter.isNegative() ? null : notFoundSettleAfter;
        batchSize = batchSize <= 0 ? 50 : batchSize;
        maxRoundsPerTick = maxRoundsPerTick <= 0 ? 20 : maxRoundsPerTick;
    }
}
