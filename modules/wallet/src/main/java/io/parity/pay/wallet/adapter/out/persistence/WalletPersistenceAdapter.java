package io.parity.pay.wallet.adapter.out.persistence;

import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.port.out.TopUpRepository;
import io.parity.pay.wallet.application.port.out.WalletBalanceRepository;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.application.service.BalanceStrategySelector;
import io.parity.pay.wallet.domain.TopUp;
import io.parity.pay.wallet.domain.TopUpStatus;
import io.parity.pay.wallet.domain.Wallet;
import io.parity.pay.wallet.domain.WalletBalance;
import io.parity.pay.wallet.domain.WalletStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 지갑·잔액·충전 out 포트의 JPA 구현. */
@Repository
class WalletPersistenceAdapter implements WalletRepository, WalletBalanceRepository, TopUpRepository {

    private final WalletJpaRepository walletJpaRepository;
    private final WalletBalanceJpaRepository balanceJpaRepository;
    private final TopUpJpaRepository topUpJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final BalanceStrategySelector strategySelector;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    WalletPersistenceAdapter(
            WalletJpaRepository walletJpaRepository,
            WalletBalanceJpaRepository balanceJpaRepository,
            TopUpJpaRepository topUpJpaRepository,
            JdbcTemplate jdbcTemplate,
            BalanceStrategySelector strategySelector,
            Clock clock) {
        this.walletJpaRepository = walletJpaRepository;
        this.balanceJpaRepository = balanceJpaRepository;
        this.topUpJpaRepository = topUpJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.strategySelector = strategySelector;
        this.clock = clock;
    }

    @Override
    public Optional<Wallet> findById(WalletId walletId) {
        return walletJpaRepository.findById(walletId.value()).map(WalletPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Wallet> findByMemberAndCurrency(MemberId memberId, CurrencyCode currency) {
        return walletJpaRepository
                .findByMemberIdAndCurrency(memberId.value(), currency.name())
                .map(WalletPersistenceAdapter::toDomain);
    }

    @Override
    public Wallet save(Wallet wallet) {
        WalletJpaEntity existing =
                entityManager.find(WalletJpaEntity.class, wallet.id().value());
        if (existing == null) {
            entityManager.persist(new WalletJpaEntity(
                    wallet.id().value(),
                    wallet.memberId().value(),
                    wallet.currency().name(),
                    wallet.status().name(),
                    wallet.createdAt()));
        } else {
            existing.changeStatus(wallet.status().name());
        }
        return wallet;
    }

    @Override
    public Optional<WalletBalance> findByWalletId(WalletId walletId) {
        return balanceJpaRepository
                .findById(walletId.value())
                .map(entity -> new WalletBalance(
                        WalletId.of(entity.walletId()),
                        Money.krw(entity.availableAmount()),
                        Money.krw(entity.pendingAmount()),
                        entity.version(),
                        entity.updatedAt()));
    }

    @Override
    public WalletBalance create(WalletId walletId, Money zero) {
        WalletBalanceJpaEntity entity = new WalletBalanceJpaEntity(walletId.value(), 0L, 0L, 0L, clock.instant());
        entityManager.persist(entity);
        return new WalletBalance(walletId, zero, zero, 0L, entity.updatedAt());
    }

    @Override
    public int increaseAvailable(WalletId walletId, Money amount) {
        return balanceJpaRepository.increaseAvailable(walletId.value(), amount.amount(), clock.instant());
    }

    @Override
    public int restoreAvailable(WalletId walletId, Money available, long expectedVersion) {
        return balanceJpaRepository.restoreAvailable(
                walletId.value(), available.amount(), expectedVersion, clock.instant());
    }

    /**
     * 잔액 차감. 두 전략 중 하나로 수행합니다.
     *
     * <p>어느 쪽이든 결과는 같아야 합니다. 잔액이 부족하면 0행이고, 동시 요청이 잔액을 초과해
     * 승인되는 일이 없어야 합니다. 차이는 처리량·지연·잠금 대기이며 그것을 측정해 ADR-004를
     * 확정합니다.
     */
    @Override
    public int decreaseAvailableIfSufficient(WalletId walletId, Money amount) {
        // 이 UPDATE가 지갑 행을 잠그고, 잠금은 커밋까지 풀리지 않습니다. 보류 중인 JPA 쓰기를
        // 먼저 내보내지 않으면 그것들이 커밋 시점에 flush되어 **잠금 안에서** 실행됩니다.
        //
        // 문장 순서를 직접 찍어 확인했습니다(M-010): 이 flush가 없으면 원장 3건과 결제 insert가
        // 차감 뒤로 밀려 잠금 보유가 26 ms였고, 있으면 차감 앞으로 나와 1 ms입니다.
        //
        // 대가: 원장·결제의 제약 위반이 커밋이 아니라 여기서 드러납니다. 어느 쪽이든 트랜잭션은
        // 되돌아가지만, 예외가 지갑 어댑터에서 나오므로 원인을 읽을 때 한 번 더 짚어야 합니다.
        // INV-001은 지연 제약 트리거라 그대로 커밋 시점에 검사됩니다.
        entityManager.flush();
        return switch (strategySelector.current()) {
            case CONDITIONAL_UPDATE -> decreaseConditionally(walletId, amount);
            case PESSIMISTIC_LOCK -> decreaseWithRowLock(walletId, amount);
            case CONDITIONAL_UPDATE_JPA -> balanceJpaRepository.decreaseAvailableIfSufficient(
                    walletId.value(), amount.amount(), clock.instant());
        };
    }

    /**
     * 잔액 조건을 WHERE에 넣은 단일 UPDATE입니다.
     *
     * <p>동시 요청은 행 잠금을 기다린 뒤 조건을 다시 평가하므로, 잔액을 넘겨 승인되는 일이
     * 없습니다. 근거: ADR-004, INV-003
     */
    private int decreaseConditionally(WalletId walletId, Money amount) {
        return jdbcTemplate.update(
                """
                UPDATE wallet_balance
                   SET available_amount = available_amount - ?,
                       version = version + 1,
                       updated_at = ?
                 WHERE wallet_id = ?
                   AND available_amount >= ?
                """,
                amount.amount(),
                Timestamp.from(clock.instant()),
                walletId.value(),
                amount.amount());
    }

    /**
     * 행을 잠그고 읽은 뒤 갱신합니다.
     *
     * <p>JPA 영속성 컨텍스트를 거치면 갱신 시점이 흐려지므로 같은 트랜잭션 안에서 JDBC로 직접
     * 실행합니다.
     */
    private int decreaseWithRowLock(WalletId walletId, Money amount) {
        List<Long> locked = jdbcTemplate.queryForList(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ? FOR UPDATE",
                Long.class,
                walletId.value());
        if (locked.isEmpty() || locked.get(0) < amount.amount()) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE wallet_balance
                   SET available_amount = available_amount - ?,
                       version = version + 1,
                       updated_at = ?
                 WHERE wallet_id = ?
                """,
                amount.amount(),
                Timestamp.from(clock.instant()),
                walletId.value());
    }

    @Override
    public Optional<TopUp> findById(TopUpId topUpId) {
        return topUpJpaRepository.findById(topUpId.value()).map(WalletPersistenceAdapter::toDomain);
    }

    @Override
    public TopUp save(TopUp topUp) {
        TopUpJpaEntity existing =
                entityManager.find(TopUpJpaEntity.class, topUp.id().value());
        if (existing == null) {
            entityManager.persist(new TopUpJpaEntity(
                    topUp.id().value(),
                    topUp.walletId().value(),
                    topUp.bankAccountId().value(),
                    topUp.requestedAmount().amount(),
                    topUp.completedAmount().amount(),
                    topUp.requestedAmount().currency().name(),
                    topUp.status().name(),
                    topUp.idempotencyKey().value(),
                    topUp.externalReferenceId(),
                    topUp.failureReason(),
                    topUp.requestedAt(),
                    topUp.completedAt()));
        } else {
            existing.applyTransition(
                    topUp.status().name(),
                    topUp.completedAmount().amount(),
                    topUp.externalReferenceId(),
                    topUp.failureReason(),
                    topUp.completedAt());
        }
        return topUp;
    }

    private static Wallet toDomain(WalletJpaEntity entity) {
        return new Wallet(
                WalletId.of(entity.walletId()),
                MemberId.of(entity.memberId()),
                CurrencyCode.valueOf(entity.currency()),
                WalletStatus.valueOf(entity.status()),
                entity.createdAt());
    }

    private static TopUp toDomain(TopUpJpaEntity entity) {
        CurrencyCode currency = CurrencyCode.valueOf(entity.currency());
        return new TopUp(
                TopUpId.of(entity.topUpId()),
                WalletId.of(entity.walletId()),
                BankAccountId.of(entity.bankAccountId()),
                Money.of(entity.requestedAmount(), currency),
                Money.of(entity.completedAmount(), currency),
                TopUpStatus.valueOf(entity.status()),
                IdempotencyKey.of(entity.idempotencyKey()),
                entity.externalReferenceId(),
                entity.failureReason(),
                entity.requestedAt(),
                entity.completedAt());
    }
}
