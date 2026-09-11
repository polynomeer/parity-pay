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
        batchSize = batchSize <= 0 ? 50 : batchSize;
        maxRoundsPerTick = maxRoundsPerTick <= 0 ? 20 : maxRoundsPerTick;
    }
}
