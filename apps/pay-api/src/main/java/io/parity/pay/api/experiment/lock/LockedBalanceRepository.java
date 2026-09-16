package io.parity.pay.api.experiment.lock;

import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.api.experiment.lock.LockExperimentProperties.Mode;
import io.parity.pay.api.experiment.lock.LockExperimentProperties.RedisDown;
import io.parity.pay.api.experiment.lock.RedisLeaseLock.ReleaseResult;
import io.parity.pay.api.experiment.lock.RedisLeaseLock.Watchdog;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.port.out.WalletBalanceRepository;
import io.parity.pay.wallet.domain.WalletBalance;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 잔액 차감을 분산락으로 감싼 **실험용** 경로. 설계가 아니며 채택하지 않습니다(ADR-004).
 *
 * <p>{@code decreaseAvailableIfSufficient}만 바꾸고 나머지는 지갑 모듈의 구현에 그대로 넘깁니다. 바꾼
 * 경로는 일부러 "락이 있으니 안전하다"고 믿는 형태입니다 — 락 안에서 잔액을 읽고, 잠시 멈추고(GC
 * 정지·네트워크 지연 흉내), 계산한 값을 **조건 없이** 저장합니다. 락 lease가 그 사이에 만료되면 무슨 일이
 * 생기는지가 M-024이고, Watchdog·fencing이 그것을 얼마나 막는지가 M-025·M-026, 같은 sleep에서 기본
 * 경로(조건부 원자 UPDATE)가 왜 안 깨지는지가 M-027입니다.
 *
 * <p>락 해제는 트랜잭션 완료 뒤({@code afterCompletion})입니다. 커밋 전에 풀면 lease 만료와 무관한 두 번째
 * 경합(다음 소유자가 아직 커밋되지 않은 값을 읽음)이 섞여 들어와 실험이 흐려집니다.
 */
class LockedBalanceRepository implements WalletBalanceRepository {

    private static final Logger log = LoggerFactory.getLogger(LockedBalanceRepository.class);
    private static final String LOCK_PREFIX = "experiment:lock:wallet:";
    private static final String TOKEN_PREFIX = "experiment:token:wallet:";

    private final WalletBalanceRepository delegate;
    private final LockExperimentProperties properties;
    private final RedisLeaseLock lock;
    private final LockHoldLog holdLog;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    LockedBalanceRepository(
            WalletBalanceRepository delegate,
            LockExperimentProperties properties,
            RedisLeaseLock lock,
            LockHoldLog holdLog,
            JdbcTemplate jdbc,
            EntityManager entityManager,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.properties = properties;
        this.lock = lock;
        this.holdLog = holdLog;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<WalletBalance> findByWalletId(WalletId walletId) {
        return delegate.findByWalletId(walletId);
    }

    @Override
    public WalletBalance create(WalletId walletId, Money zero) {
        return delegate.create(walletId, zero);
    }

    @Override
    public int increaseAvailable(WalletId walletId, Money amount) {
        return delegate.increaseAvailable(walletId, amount);
    }

    @Override
    public int restoreAvailable(WalletId walletId, Money available, long expectedVersion) {
        return delegate.restoreAvailable(walletId, available, expectedVersion);
    }

    @Override
    public int decreaseAvailableIfSufficient(WalletId walletId, Money amount) {
        return switch (properties.mode()) {
            case OFF -> delegate.decreaseAvailableIfSufficient(walletId, amount);
            case CONTROL -> decreaseWithoutLock(walletId, amount);
            case LOCK_PLAIN, LOCK_FENCE_WRITE, LOCK_FENCE_CLAIM -> decreaseUnderLock(walletId, amount);
        };
    }

    /** 대조군(d). 기본 경로 그대로, 같은 sleep만 앞에 둡니다. 겹침 구간도 같은 표에 남겨 비교합니다. */
    private int decreaseWithoutLock(WalletId walletId, Money amount) {
        UUID wallet = walletId.value();
        long holdId = holdLog.acquired(properties.runId(), Mode.CONTROL.name(), wallet, null);
        releaseAfterCompletion(holdId, null, null);
        pause();
        int updated = delegate.decreaseAvailableIfSufficient(walletId, amount);
        holdLog.wrote(holdId, null, null, updated == 1 ? "APPROVED" : "INSUFFICIENT");
        return updated;
    }

    private int decreaseUnderLock(WalletId walletId, Money amount) {
        UUID wallet = walletId.value();
        String lockKey = LOCK_PREFIX + wallet;
        Long token = acquire(lockKey, TOKEN_PREFIX + wallet);
        long holdId = holdLog.acquired(properties.runId(), properties.mode().name(), wallet, token);
        Watchdog watchdog = null;
        if (token != null && properties.watchdog()) {
            watchdog = lock.watch(lockKey, token, properties.ttl(), properties.watchdogInterval());
        }
        releaseAfterCompletion(holdId, token == null ? null : lockKey, token, watchdog);

        // 1. 락 안에서 읽습니다.
        long balance;
        if (properties.mode() == Mode.LOCK_FENCE_CLAIM && token != null) {
            OptionalLong claimed = holdLog.claimAndRead(wallet, token);
            if (claimed.isEmpty()) {
                holdLog.outcome(holdId, null, "FENCE_REJECTED_CLAIM");
                reject("FENCE_REJECTED_CLAIM");
                throw new BusinessException(
                        ErrorCode.INVALID_STATE_TRANSITION, "fence: a newer lock holder already claimed this wallet");
            }
            balance = claimed.getAsLong();
        } else {
            List<Long> rows = jdbc.queryForList(
                    "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?", Long.class, wallet);
            if (rows.isEmpty()) {
                holdLog.outcome(holdId, null, "NO_ROW");
                return 0;
            }
            balance = rows.get(0);
        }

        // 2. 락 안에서 멈춥니다. lease보다 길면 이 사이에 만료됩니다.
        pause();

        // 3. 계산해서 저장합니다. 조건이 없습니다 — 락이 지켜 준다고 믿는 구현이니까요.
        if (balance < amount.amount()) {
            holdLog.outcome(holdId, balance, "INSUFFICIENT");
            return 0;
        }
        long next = balance - amount.amount();
        entityManager.flush();
        int written =
                switch (properties.mode()) {
                    case LOCK_PLAIN -> writeUnconditionally(wallet, next);
                    case LOCK_FENCE_WRITE -> token == null
                            ? writeUnconditionally(wallet, next)
                            : writeFencedAtWrite(wallet, next, token);
                    case LOCK_FENCE_CLAIM -> token == null
                            ? writeUnconditionally(wallet, next)
                            : writeFencedAfterClaim(wallet, next, token);
                    default -> throw new IllegalStateException("not a lock mode: " + properties.mode());
                };
        if (written == 0) {
            holdLog.wrote(holdId, balance, next, "FENCE_REJECTED_WRITE");
            reject("FENCE_REJECTED_WRITE");
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "fence: lease expired before the write; a newer holder owns this wallet");
        }
        holdLog.wrote(holdId, balance, next, "APPROVED");
        return 1;
    }

    private int writeUnconditionally(UUID wallet, long next) {
        return jdbc.update(
                "UPDATE wallet_balance SET available_amount = ?, version = version + 1, updated_at = ? WHERE wallet_id = ?",
                next,
                Timestamp.from(clock.instant()),
                wallet);
    }

    /**
     * (c-1) 쓰기 시점 fencing. "저장된 토큰 &lt; 내 토큰"이면 토큰을 올리고 잔액을 씁니다.
     *
     * <p>이것이 막는 것은 **더 큰 토큰이 이미 쓴 뒤에** 오는 늦은 쓰기입니다. 새 소유자가 아직 쓰기 전이면 옛
     * 소유자의 쓰기는 통과하고, 새 소유자는 그 전에 읽어 둔 낡은 값으로 다시 씁니다.
     */
    private int writeFencedAtWrite(UUID wallet, long next, long token) {
        int fenced = jdbc.update(
                """
                INSERT INTO experiment_wallet_fence (wallet_id, fence_token) VALUES (?, ?)
                ON CONFLICT (wallet_id) DO UPDATE SET fence_token = EXCLUDED.fence_token
                WHERE experiment_wallet_fence.fence_token < EXCLUDED.fence_token
                """,
                wallet,
                token);
        if (fenced == 0) {
            return 0;
        }
        return writeUnconditionally(wallet, next);
    }

    /** (c-2) 읽을 때 새긴 토큰이 그대로일 때만 씁니다. 그 사이 더 큰 토큰이 새겨졌으면 내 읽기는 낡은 것입니다. */
    private int writeFencedAfterClaim(UUID wallet, long next, long token) {
        return jdbc.update(
                """
                UPDATE wallet_balance SET available_amount = ?, version = version + 1, updated_at = ?
                 WHERE wallet_id = ?
                   AND (SELECT fence_token FROM experiment_wallet_fence WHERE wallet_id = ?) = ?
                """,
                next,
                Timestamp.from(clock.instant()),
                wallet,
                wallet,
                token);
    }

    /**
     * 락을 얻을 때까지 짧게 반복합니다.
     *
     * @return 토큰. Redis에 닿지 못했고 FAIL_OPEN이면 null(락 없이 진행)
     */
    private Long acquire(String lockKey, String counterKey) {
        long deadline = System.nanoTime() + properties.acquireWait().toNanos();
        try {
            while (true) {
                long token = lock.tryAcquire(lockKey, counterKey, properties.ttl());
                if (token > 0) {
                    return token;
                }
                if (System.nanoTime() >= deadline) {
                    reject("LOCK_WAIT_TIMEOUT");
                    throw new BusinessException(ErrorCode.EXTERNAL_TEMPORARY_ERROR, "lock: wait timed out");
                }
                sleep(Duration.ofMillis(10));
            }
        } catch (RedisException e) {
            if (properties.onRedisDown() == RedisDown.FAIL_OPEN) {
                // 흔한 실수: "락 서버가 없으면 그냥 진행". 락이 없는 것과 같습니다.
                reject("REDIS_DOWN_FAIL_OPEN");
                log.warn("redis unavailable, proceeding WITHOUT lock (fail-open): {}", e.toString());
                return null;
            }
            reject("REDIS_DOWN_FAIL_CLOSED");
            log.warn("redis unavailable, failing the payment (fail-closed): {}", e.toString());
            throw new BusinessException(ErrorCode.EXTERNAL_TEMPORARY_ERROR, "lock: redis unavailable");
        }
    }

    private void releaseAfterCompletion(long holdId, String lockKey, Long token) {
        releaseAfterCompletion(holdId, lockKey, token, null);
    }

    private void releaseAfterCompletion(long holdId, String lockKey, Long token, Watchdog watchdog) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                int renewals = 0;
                if (watchdog != null) {
                    watchdog.cancel();
                    renewals = watchdog.renewals();
                }
                String result = "NO_LOCK";
                if (lockKey != null && token != null) {
                    ReleaseResult released = lock.release(lockKey, token);
                    result = released.name();
                    if (released != ReleaseResult.RELEASED) {
                        meterRegistry
                                .counter("paritypay.experiment.lock.release_not_owned", "result", result)
                                .increment();
                    }
                }
                holdLog.released(holdId, result, renewals, status == TransactionSynchronization.STATUS_COMMITTED);
            }
        });
    }

    private void pause() {
        int min = properties.holdMinMs();
        int max = properties.holdMaxMs();
        if (max <= 0) {
            return;
        }
        int millis = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        sleep(Duration.ofMillis(millis));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the experiment lock", e);
        }
    }

    private void reject(String reason) {
        meterRegistry
                .counter("paritypay.experiment.lock.rejected", "reason", reason)
                .increment();
    }
}
