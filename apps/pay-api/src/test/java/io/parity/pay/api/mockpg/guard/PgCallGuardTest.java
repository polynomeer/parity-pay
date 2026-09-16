package io.parity.pay.api.mockpg.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.parity.pay.api.mockpg.PgUnknownResultException;
import io.parity.pay.payment.application.port.out.PgCallRejectedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 외부 PG 호출 격리 장치의 규칙. 근거: ADR-014, reports/11 M-019~M-023
 *
 * <p>브로커·기관 없이 장치의 판정만 봅니다. 실제 부하 아래의 수치는 실험(reports/11)이 담당합니다.
 */
class PgCallGuardTest {

    private static PgResilienceProperties properties(
            PgResilienceProperties.Bulkhead bulkhead,
            PgResilienceProperties.CircuitBreaker circuit,
            PgResilienceProperties.RateLimiter limiter,
            PgResilienceProperties.Retry retry) {
        return new PgResilienceProperties(bulkhead, circuit, limiter, retry);
    }

    @Test
    @DisplayName("기본값: 벌크헤드·차단기는 켜져 있고 리미터·재시도는 꺼져 있다")
    void defaultsMatchTheDesign() {
        PgResilienceProperties defaults = properties(null, null, null, null);
        assertThat(defaults.bulkhead().enabled()).isTrue();
        assertThat(defaults.circuitBreaker().enabled()).isTrue();
        assertThat(defaults.rateLimiter().enabled()).isFalse();
        // 승인 재시도는 이중 청구입니다. 어떤 기본값으로도 켜지면 안 됩니다(ADR-007).
        assertThat(defaults.retry().enabled()).isFalse();
    }

    @Test
    @DisplayName("차단기: 실패율이 임계를 넘으면 열리고, 열린 동안의 호출은 보내지 않고 CIRCUIT_OPEN으로 거절한다")
    void circuitOpensAndRejectsWithoutCalling() {
        PgCallGuard guard = new PgCallGuard(
                properties(
                        new PgResilienceProperties.Bulkhead(false, null, null),
                        new PgResilienceProperties.CircuitBreaker(true, 4, 4, 50, Duration.ofMinutes(1), 1),
                        null,
                        null),
                new SimpleMeterRegistry());
        AtomicInteger sent = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> guard.money(() -> {
                        sent.incrementAndGet();
                        throw new PgUnknownResultException("timeout", null);
                    }))
                    .isInstanceOf(PgUnknownResultException.class);
        }
        assertThat(sent).hasValue(4);

        assertThatThrownBy(() -> guard.money(() -> {
                    sent.incrementAndGet();
                    return "would have charged";
                }))
                .isInstanceOf(PgCallRejectedException.class)
                .extracting(e -> ((PgCallRejectedException) e).reason())
                .isEqualTo("CIRCUIT_OPEN");
        // 거절은 요청이 나가지 않았다는 뜻입니다. 그래서 호출자가 미확정이 아니라 실패로 확정할 수 있습니다.
        assertThat(sent).hasValue(4);
    }

    @Test
    @DisplayName("벌크헤드: 동시 호출이 상한을 넘으면 기다리지 않고 BULKHEAD_FULL로 거절한다")
    void bulkheadRejectsBeyondTheLimit() throws Exception {
        PgCallGuard guard = new PgCallGuard(
                properties(
                        new PgResilienceProperties.Bulkhead(true, 2, Duration.ZERO),
                        new PgResilienceProperties.CircuitBreaker(false, null, null, null, null, null),
                        null,
                        null),
                new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(2);
        Runnable slowCall = () -> guard.money(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        });
        Thread first = new Thread(slowCall);
        Thread second = new Thread(slowCall);
        first.start();
        second.start();
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guard.money(() -> "third"))
                .isInstanceOf(PgCallRejectedException.class)
                .extracting(e -> ((PgCallRejectedException) e).reason())
                .isEqualTo("BULKHEAD_FULL");

        release.countDown();
        first.join();
        second.join();
        assertThat(guard.money(() -> "after")).isEqualTo("after");
    }

    @Test
    @DisplayName("리미터: 초당 상한을 넘는 호출은 RATE_LIMITED로 거절하고, 슬라이딩 윈도는 어느 1초 구간도 상한을 넘기지 않는다")
    void limitersHoldTheLineDifferently() {
        long[] clock = {0L};
        SlidingWindowLimiter sliding = new SlidingWindowLimiter(3, () -> clock[0]);
        TokenBucketLimiter bucket = new TokenBucketLimiter(3, () -> clock[0]);

        // 0초에 3건은 둘 다 통과, 4번째는 둘 다 거절
        for (int i = 0; i < 3; i++) {
            assertThat(sliding.tryAcquire()).isTrue();
            assertThat(bucket.tryAcquire()).isTrue();
        }
        assertThat(sliding.tryAcquire()).isFalse();
        assertThat(bucket.tryAcquire()).isFalse();

        // 0.5초 뒤: 토큰 버킷은 1.5개가 차 있어 1건을 더 보내지만, 슬라이딩 윈도는 지난 1초에 이미 3건이라 막습니다.
        clock[0] = 500_000_000L;
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(sliding.tryAcquire()).isFalse();

        // 1초가 지나면 슬라이딩 윈도가 다시 엽니다.
        clock[0] = 1_000_000_000L;
        assertThat(sliding.tryAcquire()).isTrue();

        PgCallGuard guard = new PgCallGuard(
                properties(
                        new PgResilienceProperties.Bulkhead(false, null, null),
                        new PgResilienceProperties.CircuitBreaker(false, null, null, null, null, null),
                        new PgResilienceProperties.RateLimiter(true, 1, "SLIDING_WINDOW"),
                        null),
                new SimpleMeterRegistry());
        assertThat(guard.money(() -> "first")).isEqualTo("first");
        assertThatThrownBy(() -> guard.money(() -> "second"))
                .isInstanceOf(PgCallRejectedException.class)
                .extracting(e -> ((PgCallRejectedException) e).reason())
                .isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("실험용 재시도: 켜면 결과를 모르는 호출을 다시 보낸다 — 그래서 기본값에서는 꺼져 있다")
    void experimentalRetryResendsUnknownCalls() {
        PgCallGuard guard = new PgCallGuard(
                properties(
                        new PgResilienceProperties.Bulkhead(false, null, null),
                        new PgResilienceProperties.CircuitBreaker(false, null, null, null, null, null),
                        null,
                        new PgResilienceProperties.Retry(true, 3, "NONE")),
                new SimpleMeterRegistry());
        AtomicInteger sent = new AtomicInteger();
        assertThatThrownBy(() -> guard.money(() -> {
                    sent.incrementAndGet();
                    throw new PgUnknownResultException("timeout", null);
                }))
                .isInstanceOf(PgUnknownResultException.class);
        // 한 결제가 기관에 세 번 도착했습니다. 기관이 멱등하지 않으면 세 번 청구됩니다(M-020).
        assertThat(sent).hasValue(3);
    }
}
