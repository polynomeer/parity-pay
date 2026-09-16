package io.parity.pay.api.experiment.lock;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Delay;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SETNX + TTL + 소유 토큰 분산락. 실험 전용입니다(M-024~M-028).
 *
 * <p>실무에서 흔한 형태 그대로입니다: 획득은 {@code SET key token NX PX ttl}, 해제는 "내 토큰일 때만
 * DEL"(조건부 해제), 토큰은 지갑별 {@code INCR}로 단조 증가시켜 fencing에도 씁니다. Redisson Watchdog
 * 흉내는 별도 스레드가 주기적으로 "내 토큰이면 PEXPIRE"를 보내는 것입니다.
 *
 * <p>여기에는 GC 정지·네트워크 지연을 이기는 장치가 없습니다. 그것이 이 실험의 주제입니다.
 */
class RedisLeaseLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaseLock.class);

    /** 키를 잡으면 지갑별 카운터를 올려 그 값을 토큰으로 저장하고 돌려줍니다. 못 잡으면 0. */
    private static final String ACQUIRE =
            """
            if redis.call('SET', KEYS[1], '0', 'NX', 'PX', ARGV[1]) then
              local t = redis.call('INCR', KEYS[2])
              redis.call('SET', KEYS[1], t, 'PX', ARGV[1])
              return t
            end
            return 0
            """;

    /** 내 토큰일 때만 연장합니다. 만료됐거나 남의 것이면 0. */
    private static final String RENEW =
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;

    /** 조건부 해제. 1 = 내 것이었고 지웠음, -1 = 이미 없음(만료), 0 = 남의 것(다른 소유자가 잡음). */
    private static final String RELEASE =
            """
            local v = redis.call('GET', KEYS[1])
            if v == false then return -1 end
            if v == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """;

    private final ClientResources resources;
    private final RedisClient client;
    private final Duration timeout;
    private final ScheduledExecutorService watchdogExecutor;
    private final Counter renewals;
    private final Counter renewalsLost;
    private final Counter redisFailures;
    private volatile StatefulRedisConnection<String, String> connection;

    RedisLeaseLock(
            String uri, Duration timeout, ScheduledExecutorService watchdogExecutor, MeterRegistry meterRegistry) {
        // Redis가 죽었다 살아나면 곧바로 다시 붙어야 (e)의 회복 시각이 재연결 백오프가 아니라 Redis의 것이 됩니다.
        this.resources = DefaultClientResources.builder()
                .reconnectDelay(Delay.constant(Duration.ofMillis(500)))
                .build();
        this.client = RedisClient.create(resources, RedisURI.create(uri));
        // 연결이 끊긴 동안 명령을 큐에 쌓지 않습니다. Redis가 죽으면 즉시 실패해야 fail-open/closed를 잴 수 있습니다.
        this.client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        this.timeout = timeout;
        this.watchdogExecutor = watchdogExecutor;
        this.renewals = meterRegistry.counter("paritypay.experiment.lock.renewals");
        this.renewalsLost = meterRegistry.counter("paritypay.experiment.lock.renewals_lost");
        this.redisFailures = meterRegistry.counter("paritypay.experiment.lock.redis_failures");
    }

    /**
     * 한 번 시도합니다.
     *
     * @return 잡았으면 토큰(&gt; 0), 다른 소유자가 있으면 0
     * @throws RedisException Redis에 닿지 못했을 때. 호출자가 fail-open/closed를 정합니다
     */
    long tryAcquire(String lockKey, String counterKey, Duration ttl) {
        try {
            Long token = connection()
                    .sync()
                    .eval(
                            ACQUIRE,
                            ScriptOutputType.INTEGER,
                            new String[] {lockKey, counterKey},
                            String.valueOf(ttl.toMillis()));
            return token == null ? 0L : token;
        } catch (RedisException e) {
            redisFailures.increment();
            throw e;
        }
    }

    boolean renew(String lockKey, long token, Duration ttl) {
        Long result = connection()
                .sync()
                .eval(
                        RENEW,
                        ScriptOutputType.INTEGER,
                        new String[] {lockKey},
                        String.valueOf(token),
                        String.valueOf(ttl.toMillis()));
        return result != null && result == 1L;
    }

    ReleaseResult release(String lockKey, long token) {
        try {
            Long result = connection()
                    .sync()
                    .eval(RELEASE, ScriptOutputType.INTEGER, new String[] {lockKey}, String.valueOf(token));
            if (result == null) {
                return ReleaseResult.REDIS_DOWN;
            }
            return switch (result.intValue()) {
                case 1 -> ReleaseResult.RELEASED;
                case -1 -> ReleaseResult.EXPIRED;
                default -> ReleaseResult.STOLEN;
            };
        } catch (RedisException e) {
            redisFailures.increment();
            return ReleaseResult.REDIS_DOWN;
        }
    }

    /**
     * Watchdog. {@code interval}마다 내 토큰이면 TTL을 다시 {@code ttl}로 늘립니다.
     *
     * <p>이 스레드가 도는 한 정상 진행 중인 트랜잭션은 lease를 잃지 않습니다. 그러나 이 스레드도 같은
     * 프로세스의 스레드입니다 — 프로세스가 멈추면(STW GC, SIGSTOP) 같이 멈춥니다.
     */
    Watchdog watch(String lockKey, long token, Duration ttl, Duration interval) {
        AtomicInteger count = new AtomicInteger();
        Watchdog watchdog = new Watchdog(count);
        ScheduledFuture<?> future = watchdogExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        if (renew(lockKey, token, ttl)) {
                            count.incrementAndGet();
                            renewals.increment();
                        } else {
                            // 만료됐거나 남이 잡았습니다. 늘릴 것이 없으므로 그만둡니다.
                            renewalsLost.increment();
                            watchdog.lost = true;
                            watchdog.cancel();
                        }
                    } catch (RedisException e) {
                        redisFailures.increment();
                        log.debug("watchdog renew failed for {}: {}", lockKey, e.toString());
                    }
                },
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
        watchdog.future = future;
        return watchdog;
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null) {
            synchronized (this) {
                if (connection == null) {
                    StatefulRedisConnection<String, String> opened = client.connect();
                    opened.setTimeout(timeout);
                    connection = opened;
                }
                current = connection;
            }
        }
        return current;
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
        client.shutdown();
        var unused = resources.shutdown();
    }

    enum ReleaseResult {
        RELEASED,
        EXPIRED,
        STOLEN,
        REDIS_DOWN
    }

    static final class Watchdog {
        private final AtomicInteger renewals;
        private volatile ScheduledFuture<?> future;
        private volatile boolean lost;

        private Watchdog(AtomicInteger renewals) {
            this.renewals = renewals;
        }

        int renewals() {
            return renewals.get();
        }

        boolean lost() {
            return lost;
        }

        void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) {
                current.cancel(false);
            }
        }
    }
}
