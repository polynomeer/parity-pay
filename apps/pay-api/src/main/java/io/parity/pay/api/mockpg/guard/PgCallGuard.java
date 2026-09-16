package io.parity.pay.api.mockpg.guard;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.api.mockpg.PgUnknownResultException;
import io.parity.pay.payment.application.port.out.PgCallRejectedException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 PG 호출 앞에 서는 격리 장치. 근거: ADR-014, reports/11 M-019~M-023
 *
 * <p>순서는 바깥에서 안으로 리미터 → 벌크헤드 → 차단기 → (재시도) → 호출입니다. 거절은 어느 층에서든
 * {@link PgCallRejectedException}으로 나가고, 그것은 **요청이 나가지 않았다**는 뜻이라 호출자가
 * 미확정이 아니라 알려진 실패로 확정합니다. 타임아웃({@link PgUnknownResultException})은 이 클래스가
 * 건드리지 않습니다 — 그것은 나갔는데 결과를 모르는 것이고 ADR-007의 영역입니다.
 *
 * <ul>
 *   <li><b>벌크헤드</b>는 느린 기관이 우리 프로세스를 통째로 잠식하는 것을 막습니다. 동시 호출이
 *       상한을 넘으면 기다리지 않고 거절합니다(M-019·M-023).
 *   <li><b>차단기</b>는 실패율이 임계를 넘으면 열려 호출을 즉시 거절하고, 대기 시간 뒤 몇 건만
 *       흘려보내 회복을 확인합니다(M-021). 타임아웃도 실패로 셉니다 — 결과를 모르는 호출이 절반이면
 *       기관은 사실상 죽은 것입니다.
 *   <li><b>리미터</b>는 기관이 정한 초당 상한을 우리 쪽에서 먼저 지킵니다. 기본은 꺼져 있습니다(M-022).
 *   <li><b>재시도</b>는 실험 전용입니다. 승인 재요청은 이중 청구입니다(M-020이 그 수치를 쟀습니다).
 * </ul>
 */
@Component
public class PgCallGuard {

    private static final Logger log = LoggerFactory.getLogger(PgCallGuard.class);

    private final PgResilienceProperties properties;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;
    private final RateLimitStrategy rateLimiter;
    private final Retry retry;
    private final Counter rejectedByCircuit;
    private final Counter rejectedByBulkhead;
    private final Counter rejectedByRateLimit;
    private final Counter retries;

    PgCallGuard(PgResilienceProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;

        this.bulkhead = properties.bulkhead().enabled()
                ? Bulkhead.of(
                        "mock-pg",
                        BulkheadConfig.custom()
                                .maxConcurrentCalls(properties.bulkhead().maxConcurrentCalls())
                                .maxWaitDuration(properties.bulkhead().maxWait())
                                .build())
                : null;

        PgResilienceProperties.CircuitBreaker cb = properties.circuitBreaker();
        this.circuitBreaker = cb.enabled()
                ? CircuitBreaker.of(
                        "mock-pg",
                        CircuitBreakerConfig.custom()
                                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(cb.slidingWindowSize())
                                .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                                .failureRateThreshold((float) cb.failureRateThreshold())
                                .waitDurationInOpenState(cb.waitDurationInOpenState())
                                .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpenState())
                                // 타임아웃(결과 모름)과 기관 쪽 오류만 실패입니다. 우리 쪽 거절은 세지 않습니다.
                                .recordException(e -> !(e instanceof PgCallRejectedException))
                                .build())
                : null;
        if (this.circuitBreaker != null) {
            // 상태 전이 시각이 곧 M-021의 측정값입니다. INFO로 남깁니다.
            this.circuitBreaker
                    .getEventPublisher()
                    .onStateTransition(event -> log.info(
                            "pg circuit {} -> {}",
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState()));
            Gauge.builder("paritypay.pg.circuit_state", this.circuitBreaker, breaker -> switch (breaker.getState()) {
                        case CLOSED -> 0;
                        case OPEN, FORCED_OPEN -> 1;
                        case HALF_OPEN -> 2;
                        default -> -1;
                    })
                    .description("외부 PG 차단기 상태 (0 CLOSED · 1 OPEN · 2 HALF_OPEN)")
                    .register(meterRegistry);
            Gauge.builder("paritypay.pg.circuit_failure_rate", this.circuitBreaker, breaker -> breaker.getMetrics()
                            .getFailureRate())
                    .description("차단기 슬라이딩 윈도의 실패율(%). 표본이 모자라면 -1")
                    .register(meterRegistry);
        }

        PgResilienceProperties.RateLimiter rl = properties.rateLimiter();
        this.rateLimiter = rl.enabled()
                ? switch (rl.algorithm()) {
                    case "FIXED_WINDOW" -> new FixedWindowLimiter(rl.limitPerSecond());
                    case "SLIDING_WINDOW" -> new SlidingWindowLimiter(rl.limitPerSecond(), System::nanoTime);
                    default -> new TokenBucketLimiter(rl.limitPerSecond(), System::nanoTime);
                }
                : null;

        PgResilienceProperties.Retry rt = properties.retry();
        this.retry = rt.enabled()
                ? Retry.of(
                        "mock-pg-experiment",
                        RetryConfig.custom()
                                .maxAttempts(rt.maxAttempts())
                                .retryExceptions(PgUnknownResultException.class)
                                .intervalBiFunction((attempt, result) -> backoffMillis(rt.backoff(), attempt))
                                .build())
                : null;
        if (this.retry != null) {
            log.warn(
                    "EXPERIMENT ONLY: pg approval retry is enabled ({} attempts, backoff {})",
                    rt.maxAttempts(),
                    rt.backoff());
        }

        if (this.bulkhead != null) {
            Gauge.builder(
                            "paritypay.pg.in_flight",
                            this.bulkhead,
                            b -> properties.bulkhead().maxConcurrentCalls()
                                    - b.getMetrics().getAvailableConcurrentCalls())
                    .description("나가 있는 PG 호출 수")
                    .register(meterRegistry);
        }
        this.rejectedByCircuit = Counter.builder("paritypay.pg.rejected")
                .tag("reason", "CIRCUIT_OPEN")
                .description("보내지 않고 거절한 PG 호출 수")
                .register(meterRegistry);
        this.rejectedByBulkhead = Counter.builder("paritypay.pg.rejected")
                .tag("reason", "BULKHEAD_FULL")
                .register(meterRegistry);
        this.rejectedByRateLimit = Counter.builder("paritypay.pg.rejected")
                .tag("reason", "RATE_LIMITED")
                .register(meterRegistry);
        this.retries = Counter.builder("paritypay.pg.retries")
                .description("실험 전용 재시도 횟수")
                .register(meterRegistry);
    }

    /** 승인·환불처럼 돈이 움직이는 호출입니다. 재시도는 실험 프로필에서만 붙습니다. */
    public <T> T money(Supplier<T> call) {
        return guarded(retry == null ? call : withRetry(call));
    }

    /** 조회입니다. 재시도는 붙이지 않습니다 — 복구 작업이 이미 자기 주기로 다시 묻습니다. */
    public <T> T query(Supplier<T> call) {
        return guarded(call);
    }

    private <T> T guarded(Supplier<T> call) {
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            rejectedByRateLimit.increment();
            throw new PgCallRejectedException("RATE_LIMITED");
        }
        Supplier<T> inner = circuitBreaker == null ? call : CircuitBreaker.decorateSupplier(circuitBreaker, call);
        Supplier<T> outer = bulkhead == null ? inner : Bulkhead.decorateSupplier(bulkhead, inner);
        try {
            return outer.get();
        } catch (BulkheadFullException e) {
            rejectedByBulkhead.increment();
            throw new PgCallRejectedException("BULKHEAD_FULL");
        } catch (CallNotPermittedException e) {
            rejectedByCircuit.increment();
            throw new PgCallRejectedException("CIRCUIT_OPEN");
        }
    }

    private <T> Supplier<T> withRetry(Supplier<T> call) {
        return () -> Retry.decorateSupplier(retry, () -> {
                    retries.increment();
                    return call.get();
                })
                .get();
    }

    /** NONE은 즉시, EXPONENTIAL은 0.5s·1s·2s…, EXPONENTIAL_JITTER는 거기에 0~100% 무작위를 곱합니다. */
    private static long backoffMillis(String backoff, int attempt) {
        return switch (backoff) {
            case "EXPONENTIAL" -> 500L << (attempt - 1);
            case "EXPONENTIAL_JITTER" -> (long)
                    ((500L << (attempt - 1)) * ThreadLocalRandom.current().nextDouble());
            default -> 0L;
        };
    }

    /**
     * 차단기를 닫힌 상태로 되돌립니다. **시험 전용입니다.** 통합 시험은 Spring 컨텍스트를 공유하므로 한
     * 시험이 열어 둔 차단기가 다음 시험의 결제를 거절합니다. 운영에서 차단기를 손으로 닫을 이유는 없습니다.
     */
    public void resetCircuitForTests() {
        if (circuitBreaker != null) {
            circuitBreaker.reset();
        }
    }

    public String describe() {
        return "bulkhead=" + (bulkhead == null ? "off" : properties.bulkhead().maxConcurrentCalls())
                + " circuit=" + (circuitBreaker == null ? "off" : "on")
                + " rateLimiter="
                + (rateLimiter == null
                        ? "off"
                        : rateLimiter.name() + "/" + properties.rateLimiter().limitPerSecond())
                + " retry="
                + (retry == null
                        ? "off"
                        : properties.retry().maxAttempts() + "x"
                                + properties.retry().backoff());
    }
}
