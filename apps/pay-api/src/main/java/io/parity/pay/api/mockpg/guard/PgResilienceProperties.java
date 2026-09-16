package io.parity.pay.api.mockpg.guard;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 PG 호출 격리 설정. 근거: ADR-014, reports/11 M-019~M-023
 *
 * <p>기본값이 곧 설계입니다 — 벌크헤드와 차단기는 켜져 있고, 리미터와 재시도는 꺼져 있습니다.
 * 리미터는 기관이 TPS 상한을 알려 줄 때 켭니다. **재시도는 실험 전용입니다.** 승인 재요청은 이중
 * 청구를 만들므로 설계는 재시도 대신 미확정·조회를 씁니다(ADR-007). 여기 있는 이유는 "재시도를
 * 켜면 무슨 일이 나는가"를 같은 코드로 재기 위해서이고, 기본값을 바꾸면 안 됩니다.
 */
@ConfigurationProperties(prefix = "paritypay.mock-pg.resilience")
public record PgResilienceProperties(
        Bulkhead bulkhead, CircuitBreaker circuitBreaker, RateLimiter rateLimiter, Retry retry) {

    public PgResilienceProperties {
        bulkhead = bulkhead == null ? new Bulkhead(null, null, null) : bulkhead;
        circuitBreaker =
                circuitBreaker == null ? new CircuitBreaker(null, null, null, null, null, null) : circuitBreaker;
        rateLimiter = rateLimiter == null ? new RateLimiter(null, null, null) : rateLimiter;
        retry = retry == null ? new Retry(null, null, null) : retry;
    }

    /** 동시에 나가 있을 수 있는 PG 호출 수. 넘치면 기다리지 않고 거절합니다. */
    public record Bulkhead(Boolean enabled, Integer maxConcurrentCalls, Duration maxWait) {
        public Bulkhead {
            enabled = enabled == null || enabled;
            maxConcurrentCalls = maxConcurrentCalls == null ? 50 : maxConcurrentCalls;
            maxWait = maxWait == null ? Duration.ZERO : maxWait;
        }
    }

    /** 최근 호출의 실패율이 임계를 넘으면 열립니다. 타임아웃(결과 모름)도 실패로 셉니다. */
    public record CircuitBreaker(
            Boolean enabled,
            Integer slidingWindowSize,
            Integer minimumNumberOfCalls,
            Integer failureRateThreshold,
            Duration waitDurationInOpenState,
            Integer permittedCallsInHalfOpenState) {
        public CircuitBreaker {
            enabled = enabled == null || enabled;
            slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
            minimumNumberOfCalls = minimumNumberOfCalls == null ? 10 : minimumNumberOfCalls;
            failureRateThreshold = failureRateThreshold == null ? 50 : failureRateThreshold;
            waitDurationInOpenState =
                    waitDurationInOpenState == null ? Duration.ofSeconds(10) : waitDurationInOpenState;
            permittedCallsInHalfOpenState = permittedCallsInHalfOpenState == null ? 3 : permittedCallsInHalfOpenState;
        }
    }

    /** 기관 TPS 상한. 알고리즘은 FIXED_WINDOW · TOKEN_BUCKET · SLIDING_WINDOW. */
    public record RateLimiter(Boolean enabled, Integer limitPerSecond, String algorithm) {
        public RateLimiter {
            enabled = enabled != null && enabled;
            limitPerSecond = limitPerSecond == null ? 50 : limitPerSecond;
            algorithm = algorithm == null ? "TOKEN_BUCKET" : algorithm;
        }
    }

    /** 실험 전용. 켜면 승인 요청을 재시도합니다 — 설계 위반을 재현하기 위한 것입니다. */
    public record Retry(Boolean enabled, Integer maxAttempts, String backoff) {
        public Retry {
            enabled = enabled != null && enabled;
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            backoff = backoff == null ? "NONE" : backoff;
        }
    }
}
