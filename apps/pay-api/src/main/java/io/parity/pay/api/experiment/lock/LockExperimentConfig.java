package io.parity.pay.api.experiment.lock;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.wallet.application.port.out.WalletBalanceRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 분산락 실험 조립. `experiment-lock` 프로필 + {@code paritypay.experiment.lock.mode != OFF}에서만 뜹니다.
 *
 * <p>지갑 모듈의 {@link WalletBalanceRepository} 구현을 {@link LockedBalanceRepository}로 감싸 {@code @Primary}로
 * 둡니다. 지갑 모듈 코드는 한 줄도 바뀌지 않았고, 이 프로필이 없으면 이 패키지는 클래스로만 존재합니다.
 * 근거: ADR-004 Outcome(2026-09-16), reports/11 M-024~M-028
 */
@Configuration
@Profile("experiment-lock")
@ConditionalOnProperty(prefix = "paritypay.experiment.lock", name = "mode")
@EnableConfigurationProperties(LockExperimentProperties.class)
class LockExperimentConfig {

    private static final Logger log = LoggerFactory.getLogger(LockExperimentConfig.class);

    /**
     * 홀드 기록·fence claim 전용 풀. 업무 풀과 섞이면 서로를 굶길 수 있습니다.
     *
     * <p>빈으로 노출하지 않습니다. DataSource 빈이 둘이 되면 JPA·Flyway·JdbcTemplate 자동설정이 물러납니다.
     */
    @Bean(destroyMethod = "close")
    LockHoldLog lockHoldLog(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            Clock clock) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("experiment-lock-pool");
        config.setMaximumPoolSize(16);
        config.setAutoCommit(true);
        LockHoldLog holdLog = new LockHoldLog(new HikariDataSource(config), clock);
        holdLog.createTables();
        return holdLog;
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService lockWatchdogExecutor() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "experiment-lock-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "close")
    RedisLeaseLock redisLeaseLock(
            LockExperimentProperties properties,
            @Qualifier("lockWatchdogExecutor") ScheduledExecutorService executor,
            MeterRegistry meterRegistry) {
        return new RedisLeaseLock(properties.redisUri(), properties.redisTimeout(), executor, meterRegistry);
    }

    @Bean
    @Primary
    WalletBalanceRepository lockedBalanceRepository(
            @Qualifier("walletPersistenceAdapter") WalletBalanceRepository delegate,
            LockExperimentProperties properties,
            RedisLeaseLock lock,
            LockHoldLog holdLog,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            Clock clock,
            MeterRegistry meterRegistry) {
        log.warn(
                "EXPERIMENT lock path active: mode={} ttl={} hold={}~{}ms watchdog={} onRedisDown={} — not the design (ADR-004)",
                properties.mode(),
                properties.ttl(),
                properties.holdMinMs(),
                properties.holdMaxMs(),
                properties.watchdog(),
                properties.onRedisDown());
        return new LockedBalanceRepository(
                delegate, properties, lock, holdLog, jdbcTemplate, entityManager, clock, meterRegistry);
    }
}
