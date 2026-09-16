package io.parity.pay.api.mockpg.guard;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * 슬라이딩 윈도 로그. 지난 1초 동안의 허가 시각을 전부 기억하고 {@code limit}개를 넘지 않게 합니다.
 *
 * <p>어느 1초 구간을 잘라도 상한을 넘지 않습니다. 고정 윈도의 경계 몰림도, 토큰 버킷의 초기 버스트도
 * 없습니다. 대가는 허가 수만큼의 메모리이고, 초당 수십~수백 건 규모에서는 문제가 아닙니다.
 */
final class SlidingWindowLimiter implements RateLimitStrategy {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final int limit;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Long> grants = new ArrayDeque<>();

    SlidingWindowLimiter(int limitPerSecond, LongSupplier nanoTime) {
        this.limit = limitPerSecond;
        this.nanoTime = nanoTime;
    }

    @Override
    public synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        while (!grants.isEmpty() && now - grants.peekFirst() >= WINDOW_NANOS) {
            grants.pollFirst();
        }
        if (grants.size() >= limit) {
            return false;
        }
        grants.addLast(now);
        return true;
    }

    @Override
    public String name() {
        return "SLIDING_WINDOW";
    }
}
