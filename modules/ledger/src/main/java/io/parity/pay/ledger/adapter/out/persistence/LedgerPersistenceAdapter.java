package io.parity.pay.ledger.adapter.out.persistence;

import io.parity.pay.ledger.application.port.out.LedgerAccountRepository;
import io.parity.pay.ledger.application.port.out.LedgerTransactionRepository;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.Direction;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerEntry;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.ledger.domain.LedgerTransactionStatus;
import io.parity.pay.ledger.domain.ReferenceType;
import io.parity.pay.ledger.domain.TransactionType;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.LedgerEntryId;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 원장 out 포트의 JPA 구현. 도메인 모델과 JPA 엔티티를 여기에서만 변환합니다.
 *
 * <p>원장은 append-only이므로 {@code merge}가 아니라 {@code persist}로만 저장합니다. Spring Data의
 * {@code save()}는 ID가 미리 지정된 엔티티를 detached로 보고 merge(=SELECT 후 UPDATE)를 시도하므로
 * 사용하지 않습니다. 근거: INV-006
 */
@Repository
class LedgerPersistenceAdapter implements LedgerAccountRepository, LedgerTransactionRepository {

    private final LedgerAccountJpaRepository accountJpaRepository;
    private final LedgerTransactionJpaRepository transactionJpaRepository;
    private final LedgerEntryJpaRepository entryJpaRepository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    LedgerPersistenceAdapter(
            LedgerAccountJpaRepository accountJpaRepository,
            LedgerTransactionJpaRepository transactionJpaRepository,
            LedgerEntryJpaRepository entryJpaRepository,
            Clock clock) {
        this.accountJpaRepository = accountJpaRepository;
        this.transactionJpaRepository = transactionJpaRepository;
        this.entryJpaRepository = entryJpaRepository;
        this.clock = clock;
    }

    @Override
    public Optional<LedgerAccount> find(AccountCode code, UUID ownerId, CurrencyCode currency) {
        return accountJpaRepository
                .findByCodeAndOwner(code.code(), ownerId, currency.name())
                .map(LedgerPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<LedgerAccount> findById(LedgerAccountId id) {
        return accountJpaRepository.findById(id.value()).map(LedgerPersistenceAdapter::toDomain);
    }

    @Override
    public LedgerAccount save(LedgerAccount account) {
        LedgerAccountJpaEntity entity = new LedgerAccountJpaEntity(
                account.id().value(),
                account.code().code(),
                account.ownerType().name(),
                account.ownerId(),
                account.currency().name(),
                account.active() ? "ACTIVE" : "INACTIVE",
                clock.instant());
        entityManager.persist(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<LedgerTransaction> findByReference(
            ReferenceType referenceType, UUID referenceId, TransactionType transactionType) {
        return transactionJpaRepository
                .findByReferenceTypeAndReferenceIdAndTransactionType(
                        referenceType.name(), referenceId, transactionType.name())
                .map(this::toDomainWithEntries);
    }

    @Override
    public Optional<LedgerTransaction> findById(LedgerTransactionId id) {
        return transactionJpaRepository.findById(id.value()).map(this::toDomainWithEntries);
    }

    @Override
    public LedgerTransaction save(LedgerTransaction transaction) {
        LedgerTransactionJpaEntity entity = new LedgerTransactionJpaEntity(
                transaction.id().value(),
                transaction.transactionType().name(),
                transaction.referenceType().name(),
                transaction.referenceId(),
                transaction.currency().name(),
                transaction.status().name(),
                transaction.reversalOfTransactionId() == null
                        ? null
                        : transaction.reversalOfTransactionId().value(),
                transaction.effectiveAt(),
                transaction.createdAt());
        entityManager.persist(entity);

        for (LedgerEntry entry : transaction.entries()) {
            entityManager.persist(new LedgerEntryJpaEntity(
                    entry.id().value(),
                    transaction.id().value(),
                    entry.accountId().value(),
                    entry.direction().name(),
                    entry.money().amount(),
                    transaction.createdAt()));
        }
        return transaction;
    }

    @Override
    public DebitCreditTotals totalsOf(LedgerAccountId accountId) {
        LedgerEntryJpaRepository.EntryTotalsProjection totals =
                entryJpaRepository.sumTotalsByAccountId(accountId.value());
        return new DebitCreditTotals(totals.getDebitTotal(), totals.getCreditTotal());
    }

    private static LedgerAccount toDomain(LedgerAccountJpaEntity entity) {
        return new LedgerAccount(
                LedgerAccountId.of(entity.accountId()),
                AccountCode.fromCode(entity.accountCode()),
                entity.ownerId(),
                CurrencyCode.valueOf(entity.currency()),
                "ACTIVE".equals(entity.status()));
    }

    private LedgerTransaction toDomainWithEntries(LedgerTransactionJpaEntity entity) {
        CurrencyCode currency = CurrencyCode.valueOf(entity.currency());
        List<LedgerEntry> entries =
                entryJpaRepository.findByTransactionIdOrderByEntryId(entity.transactionId()).stream()
                        .map(e -> new LedgerEntry(
                                LedgerEntryId.of(e.entryId()),
                                LedgerAccountId.of(e.accountId()),
                                Direction.valueOf(e.direction()),
                                Money.of(e.amount(), currency)))
                        .toList();

        return new LedgerTransaction(
                LedgerTransactionId.of(entity.transactionId()),
                ReferenceType.valueOf(entity.referenceType()),
                entity.referenceId(),
                TransactionType.valueOf(entity.transactionType()),
                currency,
                LedgerTransactionStatus.valueOf(entity.status()),
                entity.reversalOfTransactionId() == null
                        ? null
                        : LedgerTransactionId.of(entity.reversalOfTransactionId()),
                entity.effectiveAt(),
                entity.createdAt(),
                entries);
    }
}
