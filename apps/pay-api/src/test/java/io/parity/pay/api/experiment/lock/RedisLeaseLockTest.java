package io.parity.pay.api.experiment.lock;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.parity.pay.api.experiment.lock.RedisLeaseLock.ReleaseResult;
import io.parity.pay.api.experiment.lock.RedisLeaseLock.Watchdog;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실험용 분산락 원시 연산의 의미. 근거: reports/11 M-024~M-026, ADR-004 Outcome(2026-09-16)
 *
 * <p>이 락은 설계가 아닙니다. 이 시험이 지키는 것은 "실험이 잰 것이 락의 버그가 아니라 lease 모델 자체의
 * 성질"이라는 사실입니다 — 락은 정확히 SETNX+TTL·조건부 해제·단조 토큰대로 동작하고, 그럼에도 M-024가
 * 깨집니다.
 */
@Testcontainers
class RedisLeaseLockTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static ScheduledExecutorService executor;
    private static RedisLeaseLock lock;

    @BeforeAll
    static void connect() {
        executor = Executors.newSingleThreadScheduledExecutor();
        lock = new RedisLeaseLock(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                Duration.ofSeconds(2),
                executor,
                new SimpleMeterRegistry());
    }

    @AfterAll
    static void disconnect() {
        lock.close();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("토큰은 지갑별로 단조 증가하고, 잡혀 있는 동안의 획득은 0이며, 조건부 해제는 내 토큰만 지운다")
    void acquireIsExclusiveAndTokensIncrease() {
        long first = lock.tryAcquire("t:lock:a", "t:token:a", Duration.ofSeconds(5));
        assertThat(first).isPositive();
        assertThat(lock.tryAcquire("t:lock:a", "t:token:a", Duration.ofSeconds(5)))
                .isZero();

        assertThat(lock.release("t:lock:a", first + 100)).isEqualTo(ReleaseResult.STOLEN);
        assertThat(lock.release("t:lock:a", first)).isEqualTo(ReleaseResult.RELEASED);

        long second = lock.tryAcquire("t:lock:a", "t:token:a", Duration.ofSeconds(5));
        assertThat(second).isGreaterThan(first);
        assertThat(lock.release("t:lock:a", second)).isEqualTo(ReleaseResult.RELEASED);
    }

    @Test
    @DisplayName("lease가 만료되면 두 번째 소유자가 생기고, 첫 소유자는 해제 시점에야 그것을 안다(STOLEN)")
    void expiredLeaseAdmitsASecondOwner() throws InterruptedException {
        long first = lock.tryAcquire("t:lock:b", "t:token:b", Duration.ofMillis(150));
        assertThat(first).isPositive();
        Thread.sleep(300);
        long second = lock.tryAcquire("t:lock:b", "t:token:b", Duration.ofSeconds(5));
        // 두 소유자가 동시에 "락을 쥐고 있다"고 믿는 구간입니다. M-024가 이 구간에서 잔액을 두 번 씁니다.
        assertThat(second).isGreaterThan(first);
        assertThat(lock.release("t:lock:b", first)).isEqualTo(ReleaseResult.STOLEN);
        assertThat(lock.release("t:lock:b", second)).isEqualTo(ReleaseResult.RELEASED);
    }

    @Test
    @DisplayName("Watchdog이 도는 동안은 lease보다 오래 쥐어도 만료되지 않고, 멈추면 만료된다")
    void watchdogKeepsTheLeaseOnlyWhileItRuns() throws InterruptedException {
        long token = lock.tryAcquire("t:lock:c", "t:token:c", Duration.ofMillis(200));
        Watchdog watchdog = lock.watch("t:lock:c", token, Duration.ofMillis(200), Duration.ofMillis(60));
        Thread.sleep(500);
        assertThat(lock.tryAcquire("t:lock:c", "t:token:c", Duration.ofMillis(200)))
                .as("연장 중에는 두 번째 소유자가 생기지 않는다")
                .isZero();
        assertThat(watchdog.renewals()).isGreaterThanOrEqualTo(3);

        // 연장 스레드가 멈춘 것(프로세스 정지와 같은 효과)
        watchdog.cancel();
        Thread.sleep(350);
        assertThat(lock.release("t:lock:c", token)).isEqualTo(ReleaseResult.EXPIRED);
    }
}
