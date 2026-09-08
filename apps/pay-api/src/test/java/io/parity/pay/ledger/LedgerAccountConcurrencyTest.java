package io.parity.pay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 최초 생성의 동시성.
 *
 * <p>P-004에서 나온 결함입니다. 계정은 첫 사용 시점에 만들어지므로, 부하가 시작되는 순간 같은
 * 계정을 여러 요청이 동시에 만들려 합니다. 조회 후 없으면 삽입하는 방식은 그때 유니크 제약에
 * 걸리고, PostgreSQL에서 제약 위반은 트랜잭션 전체를 중단시키므로 잡아서 넘길 수도 없습니다.
 * 그 결과 정상 결제가 500으로 실패했습니다.
 *
 * <p>근거: reports/11 P-004, docs/09-consistency-recovery.md §3
 */
class LedgerAccountConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    private ResolveLedgerAccountUseCase resolveAccount;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("같은 계정을 동시에 만들어도 한 행만 생기고 아무도 실패하지 않는다")
    void concurrentFirstUseCreatesExactlyOneAccount() throws Exception {
        UUID ownerId = UUID.randomUUID();

        List<LedgerAccount> resolved = runConcurrently(() -> transactionTemplate.execute(
                status -> resolveAccount.resolve(AccountCode.USER_PAY_MONEY, ownerId, CurrencyCode.KRW)));

        assertThat(resolved).hasSize(THREADS).allSatisfy(account -> assertThat(account.ownerId())
                .isEqualTo(ownerId));
        // 모두 같은 계정을 가리켜야 합니다. 다른 ID가 섞이면 같은 소유자의 잔액이 두 계정으로
        // 갈라진 것입니다.
        assertThat(resolved.stream().map(LedgerAccount::id).distinct()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM ledger_account WHERE owner_id = ?", Long.class, ownerId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("법인 계정도 동시 최초 사용에서 한 행만 생긴다")
    void concurrentFirstUseOfACorporateAccountCreatesOneRow() throws Exception {
        // 법인 계정은 owner_id가 NULL입니다. NULL을 같은 값으로 취급하는 제약이 실제로 걸리는지도
        // 함께 봅니다.
        jdbcTemplate.update(
                "DELETE FROM ledger_entry WHERE account_id IN"
                        + " (SELECT account_id FROM ledger_account WHERE account_code = ? AND owner_id IS NULL)",
                AccountCode.PLATFORM_FEE_REVENUE.code());
        jdbcTemplate.update(
                "DELETE FROM ledger_account WHERE account_code = ? AND owner_id IS NULL",
                AccountCode.PLATFORM_FEE_REVENUE.code());

        List<LedgerAccount> resolved = runConcurrently(() -> transactionTemplate.execute(
                status -> resolveAccount.resolveCorporate(AccountCode.PLATFORM_FEE_REVENUE, CurrencyCode.KRW)));

        assertThat(resolved.stream().map(LedgerAccount::id).distinct()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM ledger_account WHERE account_code = ? AND owner_id IS NULL",
                        Long.class,
                        AccountCode.PLATFORM_FEE_REVENUE.code()))
                .isEqualTo(1L);
    }

    /** 모든 스레드를 같은 순간에 풀어놓습니다. 순차 실행이면 이 결함은 재현되지 않습니다. */
    private List<LedgerAccount> runConcurrently(Callable<LedgerAccount> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<LedgerAccount>> futures = IntStream.range(0, THREADS)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return work.call();
                    }))
                    .toList();
            start.countDown();

            List<LedgerAccount> results = new java.util.ArrayList<>();
            for (Future<LedgerAccount> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
