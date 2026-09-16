package io.parity.pay.api.experiment.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 분산락 실험(M-024~M-028) 설정. **설계가 아닙니다.**
 *
 * <p>ParityPay는 잔액 차감에 분산락을 쓰지 않기로 했습니다(ADR-004). 이 설정으로 켜지는 경로는 "락이
 * 정합성을 지켜 준다고 믿는 구현"을 일부러 만든 것이고, 락 lease가 트랜잭션보다 먼저 만료될 때 정말
 * 정합성이 깨지는지, 그리고 조건부 원자 UPDATE는 같은 조건에서 왜 깨지지 않는지를 재기 위해서만
 * 존재합니다. `experiment-lock` 프로필 밖에서는 아무것도 만들어지지 않습니다.
 *
 * @param mode 어느 경로로 차감할지. {@link Mode#OFF}면 실험 빈을 만들지 않습니다
 * @param redisUri 실험용 Redis. compose의 `redis` 서비스입니다
 * @param ttl 락 lease. 트랜잭션보다 짧게 두면 lease 만료를 재현합니다
 * @param holdMinMs 락 안에서 잠드는 최소 시간 — GC 정지·네트워크 지연 흉내
 * @param holdMaxMs 락 안에서 잠드는 최대 시간. 구간 안에서 균등 난수입니다
 * @param acquireWait 락을 얻을 때까지 기다리는 상한. 넘기면 결제는 실패합니다
 * @param watchdog 락 보유 중 TTL을 주기적으로 늘리는 스레드(Redisson Watchdog 흉내)
 * @param watchdogInterval 연장 주기. TTL/3이 통상값입니다
 * @param onRedisDown Redis에 닿지 못할 때 — 락 없이 진행(FAIL_OPEN)할지 결제를 실패시킬지(FAIL_CLOSED)
 * @param redisTimeout Redis 명령 타임아웃. Redis가 죽었을 때 이 시간 안에 실패해야 합니다
 * @param runId 홀드 기록에 남길 실행 식별자. 하니스가 실행마다 바꿉니다
 */
@ConfigurationProperties(prefix = "paritypay.experiment.lock")
public record LockExperimentProperties(
        Mode mode,
        String redisUri,
        Duration ttl,
        Integer holdMinMs,
        Integer holdMaxMs,
        Duration acquireWait,
        Boolean watchdog,
        Duration watchdogInterval,
        RedisDown onRedisDown,
        Duration redisTimeout,
        String runId) {

    public LockExperimentProperties {
        mode = mode == null ? Mode.OFF : mode;
        redisUri = redisUri == null ? "redis://localhost:6379" : redisUri;
        ttl = ttl == null ? Duration.ofMillis(200) : ttl;
        holdMinMs = holdMinMs == null ? 300 : holdMinMs;
        holdMaxMs = holdMaxMs == null ? 500 : holdMaxMs;
        acquireWait = acquireWait == null ? Duration.ofSeconds(5) : acquireWait;
        watchdog = watchdog == null ? Boolean.FALSE : watchdog;
        watchdogInterval = watchdogInterval == null ? ttl.dividedBy(3) : watchdogInterval;
        onRedisDown = onRedisDown == null ? RedisDown.FAIL_CLOSED : onRedisDown;
        redisTimeout = redisTimeout == null ? Duration.ofMillis(500) : redisTimeout;
        runId = runId == null ? "" : runId;
        if (holdMaxMs < holdMinMs) {
            throw new IllegalArgumentException("hold-max-ms must be >= hold-min-ms");
        }
    }

    public enum Mode {
        /** 실험 빈을 만들지 않습니다. 기본 경로 그대로입니다. */
        OFF,
        /**
         * SETNX+TTL 락 안에서 "조회 → 계산 → 저장"(비조건부 UPDATE). 락이 정합성을 지킨다고 믿는 구현입니다.
         * (a)·(b)·(e)
         */
        LOCK_PLAIN,
        /**
         * LOCK_PLAIN + 쓰기 시점 fencing: 잔액 UPDATE 직전에 "저장된 토큰 &lt; 내 토큰"이면 토큰을 올리고,
         * 아니면 거부합니다. 흔히 설명되는 형태입니다. (c-1)
         */
        LOCK_FENCE_WRITE,
        /**
         * 읽는 순간 토큰을 새깁니다: 별도 짧은 트랜잭션에서 "저장된 토큰 &lt; 내 토큰"일 때만 토큰을 올리고
         * 잔액을 읽습니다. 쓰기는 "저장된 토큰 = 내 토큰"일 때만 통과합니다. (c-2)
         */
        LOCK_FENCE_CLAIM,
        /** 분산락 없이 기본 경로(조건부 원자 UPDATE)로, 같은 sleep만 주입합니다. 대조군 (d) */
        CONTROL
    }

    public enum RedisDown {
        /** 락을 못 얻으면 결제를 실패시킵니다. */
        FAIL_CLOSED,
        /** 락 없이 진행합니다. 흔한 실수를 재현하기 위한 값입니다. */
        FAIL_OPEN
    }
}
