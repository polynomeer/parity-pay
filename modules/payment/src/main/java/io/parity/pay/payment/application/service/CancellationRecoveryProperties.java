package io.parity.pay.payment.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 미확정 취소(환불) 복구 설정.
 *
 * <p>{@code grace}는 정상 처리 중인 요청을 복구가 가로채지 않도록 두는 여유입니다. 이것이 없으면
 * 외부 응답을 기다리는 중인 결제를 복구가 동시에 조회합니다.
 *
 * <p>{@code notFoundConfirmThreshold}는 "외부에 기록이 없다"를 몇 번 연속 확인해야 환불이 나가지 않았다고
 * 결론 내릴지입니다. 한 번의 조회로 단정하지 않습니다.
 */
@ConfigurationProperties(prefix = "paritypay.recovery.cancellation")
public record CancellationRecoveryProperties(
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

    public CancellationRecoveryProperties {
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
