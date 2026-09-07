package io.parity.pay.performance;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.service.BalanceStrategySelector;
import io.parity.pay.wallet.application.service.BalanceStrategySelector.Strategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-004 비교 측정: 조건부 원자 갱신 vs 비관적 잠금.
 *
 * <p>같은 지갑에 동시 결제를 몰아넣고 두 전략의 처리 시간과 결과를 비교합니다. 이 테스트의 1차
 * 목적은 <b>정확성</b>입니다. 어느 전략이든 승인 합계가 잔액을 넘지 않아야 합니다(INV-003).
 * 처리 시간은 부수적으로 얻는 참고값이며, 개발 노트북에서 컨테이너와 함께 측정한 값이므로 운영
 * 성능이 아닙니다. 실행 환경과 함께 reports/11에 기록합니다.
 *
 * <p>기본 테스트 실행에서는 제외합니다. 여러 라운드를 돌아 몇 분이 걸리고, 결과가 실행 환경에
 * 민감해 PR 게이트로 쓰기에 적합하지 않습니다. 실행하려면:
 * {@code ./gradlew test -PincludeBenchmarks --tests "*BalanceStrategyBenchmarkTest"}
 *
 * <p>근거: ADR-004, docs/10-test-strategy.md §7, docs/11 P-002
 */
@Tag("benchmark")
class BalanceStrategyBenchmarkTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;
    private static final int PAYMENTS_PER_THREAD = 5;
    /** 측정 전에 버리는 예열 라운드입니다. JIT·커넥션 풀·캐시가 자리를 잡습니다. */
    private static final int WARMUP_ROUNDS = 1;
    /** 실행 순서에 따른 편차를 상쇄하기 위해 전략을 번갈아 여러 번 돌립니다. */
    private static final int MEASURED_ROUNDS = 3;

    private static final long PAYMENT_AMOUNT = 1_000L;
    /** 절반만 승인될 수 있는 잔액을 넣어 경쟁과 거절을 동시에 만듭니다. */
    private static final long INITIAL_BALANCE = THREADS * PAYMENTS_PER_THREAD * PAYMENT_AMOUNT / 2;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private BalanceStrategySelector strategySelector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreDefaultStrategy() {
        strategySelector.use(Strategy.CONDITIONAL_UPDATE);
    }

    @Test
    @DisplayName("ADR-004: 세 구현 모두 잔액을 초과 승인하지 않으며, 같은 조건에서 시간을 비교한다")
    void compareBalanceStrategies() throws Exception {
        List<Strategy> strategies =
                List.of(Strategy.CONDITIONAL_UPDATE, Strategy.PESSIMISTIC_LOCK, Strategy.CONDITIONAL_UPDATE_JPA);

        // 예열 결과는 버립니다. 근거: docs/10-test-strategy.md §7 (warm-up과 측정 구간 분리)
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            for (Strategy strategy : strategies) {
                runScenario(strategy);
            }
        }

        Map<Strategy, List<Result>> measurements = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            measurements.put(strategy, new ArrayList<>());
        }
        // 전략을 번갈아 돌려 실행 순서에 따른 편차를 상쇄합니다.
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            for (Strategy strategy : strategies) {
                measurements.get(strategy).add(runScenario(strategy));
            }
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "[ADR-004] 동일 지갑 경합 벤치마크%n"
                        + "  부하: %d threads x %d payments = %d ops, 초기 잔액 %d원 (절반만 승인 가능)%n"
                        + "  라운드: warm-up %d회(버림) + 측정 %d회(전략 교대 실행)%n"
                        + "  환경: java=%s, os=%s %s, cores=%d, PostgreSQL=Testcontainers(postgres:17-alpine)%n",
                THREADS,
                PAYMENTS_PER_THREAD,
                THREADS * PAYMENTS_PER_THREAD,
                INITIAL_BALANCE,
                WARMUP_ROUNDS,
                MEASURED_ROUNDS,
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors()));

        measurements.forEach((strategy, results) -> {
            List<Long> millis = results.stream()
                    .map(result -> result.elapsed().toMillis())
                    .sorted()
                    .toList();
            long median = millis.get(millis.size() / 2);
            report.append(String.format(
                    "  %-22s median=%5d ms  min=%5d ms  max=%5d ms  approved=%d/%d%n",
                    strategy.name(),
                    median,
                    millis.get(0),
                    millis.get(millis.size() - 1),
                    results.get(0).approved(),
                    THREADS * PAYMENTS_PER_THREAD));

            // 정확성이 1차 기준입니다. 어떤 구현도 잔액을 넘겨 승인하지 않아야 합니다.
            results.forEach(result -> {
                assertThat(result.approved() * PAYMENT_AMOUNT).isEqualTo(INITIAL_BALANCE);
                assertThat(result.finalBalance()).isZero();
                assertThat(result.approved() + result.rejected()).isEqualTo(THREADS * PAYMENTS_PER_THREAD);
            });
        });

        double spread = measurements.values().stream()
                        .flatMap(List::stream)
                        .mapToLong(result -> result.elapsed().toMillis())
                        .max()
                        .orElse(0)
                / (double) Math.max(
                        1,
                        measurements.values().stream()
                                .flatMap(List::stream)
                                .mapToLong(result -> result.elapsed().toMillis())
                                .min()
                                .orElse(1));
        report.append(String.format("  전체 최대/최소 비율: %.1fx%n", spread));

        System.out.println();
        System.out.print(report);
        writeReport(report.toString());
    }

    /** 보고서에서 인용할 수 있도록 원본 결과를 파일로 남깁니다. 근거: docs/10-test-strategy.md §12 */
    private void writeReport(String report) {
        try {
            Path path = Path.of("build", "reports", "adr-004-balance-strategy.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
            System.out.println("  결과 파일: " + path.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("  결과 파일을 남기지 못했습니다: " + e.getMessage());
        }
    }

    private static final AtomicLong SCENARIO_SEQUENCE = new AtomicLong();

    private Result runScenario(Strategy strategy) throws Exception {
        strategySelector.use(strategy);
        Fixture fixture = freshWallet(
                strategy.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + SCENARIO_SEQUENCE.incrementAndGet());

        AtomicInteger approved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        long startedAt;
        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            for (int thread = 0; thread < THREADS; thread++) {
                int threadIndex = thread;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < PAYMENTS_PER_THREAD; i++) {
                        String suffix = fixture.prefix() + "-" + threadIndex + "-" + i;
                        try {
                            approvePayment.approve(new ApprovePaymentCommand(
                                    fixture.memberId(),
                                    "order-" + suffix,
                                    fixture.walletId(),
                                    fixture.merchantId(),
                                    Money.krw(PAYMENT_AMOUNT),
                                    PaymentMethod.PAY_MONEY,
                                    IdempotencyKey.of("bench-" + suffix)));
                            approved.incrementAndGet();
                        } catch (BusinessException e) {
                            rejected.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            startedAt = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        long finalBalance = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?",
                Long.class,
                fixture.walletId().value());

        return new Result(elapsed, approved.get(), rejected.get(), finalBalance);
    }

    /** 시나리오마다 새 회원·지갑을 만들어 서로 간섭하지 않게 합니다. */
    private Fixture freshWallet(String prefix) {
        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("bench-" + prefix + "@example.com", "password1234");
        MemberId memberId = registered.memberId();
        WalletId walletId = WalletId.of(registered.walletId());
        BankAccountId bankAccountId =
                onboardingService.linkBankAccount(memberId, "004", "110-" + prefix, Money.krw(INITIAL_BALANCE * 2));
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                walletId,
                bankAccountId,
                Money.krw(INITIAL_BALANCE),
                IdempotencyKey.of("bench-topup-" + prefix)));
        return new Fixture(memberId, walletId, MerchantId.generate(), prefix);
    }

    private record Fixture(MemberId memberId, WalletId walletId, MerchantId merchantId, String prefix) {}

    private record Result(Duration elapsed, int approved, int rejected, long finalBalance) {}
}
