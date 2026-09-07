package io.parity.pay.ledger.testing;

import io.parity.pay.ledger.application.port.out.LedgerTransactionRepository;
import io.parity.pay.ledger.domain.LedgerEntry;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.ledger.domain.ReferenceType;
import io.parity.pay.ledger.domain.TransactionType;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.LedgerTransactionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 단위 테스트용 인메모리 원장 거래 저장소.
 *
 * <p>업무 참조 유니크 제약을 흉내 내어, 같은 참조로 두 번 저장하면 예외를 던집니다.
 * 근거: INV-004
 */
public class InMemoryLedgerTransactionRepository implements LedgerTransactionRepository {

    private final Map<UUID, LedgerTransaction> transactions = new ConcurrentHashMap<>();
    private final List<String> referenceKeys = new ArrayList<>();

    @Override
    public Optional<LedgerTransaction> findByReference(
            ReferenceType referenceType, UUID referenceId, TransactionType transactionType) {
        return transactions.values().stream()
                .filter(transaction -> transaction.referenceType() == referenceType
                        && transaction.referenceId().equals(referenceId)
                        && transaction.transactionType() == transactionType)
                .findFirst();
    }

    @Override
    public Optional<LedgerTransaction> findById(LedgerTransactionId id) {
        return Optional.ofNullable(transactions.get(id.value()));
    }

    @Override
    public synchronized LedgerTransaction save(LedgerTransaction transaction) {
        String key = referenceKey(transaction);
        if (referenceKeys.contains(key)) {
            throw new IllegalStateException("duplicate ledger transaction for reference: " + key);
        }
        referenceKeys.add(key);
        transactions.put(transaction.id().value(), transaction);
        return transaction;
    }

    @Override
    public DebitCreditTotals totalsOf(LedgerAccountId accountId) {
        long debit = 0L;
        long credit = 0L;
        for (LedgerTransaction transaction : transactions.values()) {
            for (LedgerEntry entry : transaction.entries()) {
                if (!entry.accountId().equals(accountId)) {
                    continue;
                }
                if (entry.isDebit()) {
                    debit += entry.money().amount();
                } else {
                    credit += entry.money().amount();
                }
            }
        }
        return new DebitCreditTotals(debit, credit);
    }

    public int size() {
        return transactions.size();
    }

    private static String referenceKey(LedgerTransaction transaction) {
        return transaction.referenceType() + "|" + transaction.referenceId() + "|" + transaction.transactionType();
    }
}
