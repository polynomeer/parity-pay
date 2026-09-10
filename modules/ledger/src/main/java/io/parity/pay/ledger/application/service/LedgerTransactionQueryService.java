package io.parity.pay.ledger.application.service;

import io.parity.pay.ledger.application.port.in.LedgerTransactionQuery;
import io.parity.pay.ledger.application.port.out.LedgerAccountRepository;
import io.parity.pay.ledger.application.port.out.LedgerTransactionRepository;
import io.parity.pay.ledger.domain.LedgerEntry;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.money.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link LedgerTransactionQuery} 구현입니다. */
@Service
class LedgerTransactionQueryService implements LedgerTransactionQuery {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;

    LedgerTransactionQueryService(
            LedgerTransactionRepository transactionRepository, LedgerAccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LedgerTransactionView> findById(LedgerTransactionId id) {
        return transactionRepository.findById(id).map(this::toView);
    }

    private LedgerTransactionView toView(LedgerTransaction transaction) {
        List<EntryView> entries =
                transaction.entries().stream().map(this::toEntryView).toList();
        Money debitTotal = total(transaction, true);
        Money creditTotal = total(transaction, false);
        return new LedgerTransactionView(
                transaction.id().value(),
                transaction.referenceType().name(),
                transaction.referenceId(),
                transaction.transactionType().name(),
                transaction.currency().name(),
                transaction.status().name(),
                transaction.reversalOfTransactionId() == null
                        ? null
                        : transaction.reversalOfTransactionId().value(),
                transaction.effectiveAt(),
                transaction.createdAt(),
                entries,
                debitTotal,
                creditTotal,
                // INV-001은 DB가 강제하지만, 화면이 그 사실을 **보여 줄** 수 있어야 합니다.
                debitTotal.equals(creditTotal));
    }

    private EntryView toEntryView(LedgerEntry entry) {
        // 계정 코드가 없으면 화면에 계정 ID만 남습니다. 운영자에게 읽히지 않습니다.
        String accountCode = accountRepository
                .findById(entry.accountId())
                .map(account -> account.code().name())
                .orElse("UNKNOWN");
        var ownerId = accountRepository
                .findById(entry.accountId())
                .map(account -> account.ownerId())
                .orElse(null);
        return new EntryView(
                entry.id().value(),
                entry.accountId().value(),
                accountCode,
                ownerId,
                entry.direction().name(),
                entry.money());
    }

    private Money total(LedgerTransaction transaction, boolean debit) {
        return transaction.entries().stream()
                .filter(entry -> entry.isDebit() == debit)
                .map(LedgerEntry::money)
                .reduce(Money.zero(transaction.currency()), Money::plus);
    }
}
