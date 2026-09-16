package io.parity.pay.api.mockpg.guard;

import java.util.function.LongSupplier;

/**
 * 토큰 버킷. 초당 {@code rate}개가 채워지고 최대 {@code rate}개까지 쌓입니다.
 *
 * <p>쉬었다가 몰리는 트래픽을 버킷 크기만큼 한꺼번에 통과시킵니다. 그것이 장점이자 단점입니다 —
 * 기관의 상한이 "평균"이면 좋고 "순간"이면 넘칩니다(M-022).
 *
 * <p>토큰은 백만분의 일 단위 정수로 셉니다. 이 저장소는 어떤 필드에도 부동소수점을 두지 않습니다
 * (ArchUnit) — 금액이 아니어도 규칙은 하나입니다.
 */
final class TokenBucketLimiter implements RateLimitStrategy {

    private static final long MICRO = 1_000_000L;

    private final long capacityMicro;
    private final long ratePerSecond;
    private final LongSupplier nanoTime;
    private long tokensMicro;
    private long lastRefill;

    TokenBucketLimiter(int ratePerSecond, LongSupplier nanoTime) {
        this.capacityMicro = ratePerSecond * MICRO;
        this.ratePerSecond = ratePerSecond;
        this.nanoTime = nanoTime;
        this.tokensMicro = capacityMicro;
        this.lastRefill = nanoTime.getAsLong();
    }

    @Override
    public synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        // 초당 rate 토큰 = 나노초당 rate × MICRO / 1e9 마이크로토큰 = 나노초당 rate / 1000 마이크로토큰
        long refilled = (now - lastRefill) * ratePerSecond / 1_000L;
        tokensMicro = Math.min(capacityMicro, tokensMicro + refilled);
        lastRefill = now;
        if (tokensMicro >= MICRO) {
            tokensMicro -= MICRO;
            return true;
        }
        return false;
    }

    @Override
    public String name() {
        return "TOKEN_BUCKET";
    }
}
