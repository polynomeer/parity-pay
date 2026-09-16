package io.parity.pay.api.mockpg.guard;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;

/**
 * 고정 윈도. resilience4j {@code RateLimiter}의 기본 동작 — 1초마다 허가 수가 {@code limit}로 리셋됩니다.
 *
 * <p>구현이 단순하고 값이 싸지만 경계에서 몰립니다. 한 윈도의 끝과 다음 윈도의 처음이 붙으면 어느
 * 1초 구간에는 상한의 두 배가 지나갑니다(M-022가 그 수치를 쟀습니다).
 */
final class FixedWindowLimiter implements RateLimitStrategy {

    private final RateLimiter limiter;

    FixedWindowLimiter(int limitPerSecond) {
        this.limiter = RateLimiter.of(
                "mock-pg",
                RateLimiterConfig.custom()
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .limitForPeriod(limitPerSecond)
                        .timeoutDuration(Duration.ZERO)
                        .build());
    }

    @Override
    public boolean tryAcquire() {
        return limiter.acquirePermission();
    }

    @Override
    public String name() {
        return "FIXED_WINDOW";
    }
}
