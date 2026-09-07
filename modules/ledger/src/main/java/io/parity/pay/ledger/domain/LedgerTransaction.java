package io.parity.pay.ledger.domain;

import io.parity.pay.shared.id.LedgerEntryId;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.money.CurrencyCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 전기된 원장 거래.
 *
 * <p>{@link #post(LedgerTransactionId, Journal, Instant)}로만 만들 수 있고, 만들어진 뒤에는 상태를
 * 바꾸는 메서드를 제공하지 않습니다. 역분개는 원거래를 참조하는 새 거래입니다.
 *
 * <p>근거: docs/07-ledger-journal-catalog.md §4, ADR-009
 */
public record LedgerTransaction(
        LedgerTransactionId id,
        ReferenceType referenceType,
        UUID referenceId,
        TransactionType transactionType,
        CurrencyCode currency,
        LedgerTransactionStatus status,
        LedgerTransactionId reversalOfTransactionId,
        Instant effectiveAt,
        Instant createdAt,
        List<LedgerEntry> entries) {

    public LedgerTransaction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Objects.requireNonNull(transactionType, "transactionType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));

        long debitTotal = 0L;
        long creditTotal = 0L;
        for (LedgerEntry entry : entries) {
            if (entry.money().currency() != currency) {
                throw new UnbalancedJournalException("ledger transaction must use a single currency");
            }
            if (entry.isDebit()) {
                debitTotal = Math.addExact(debitTotal, entry.money().amount());
            } else {
                creditTotal = Math.addExact(creditTotal, entry.money().amount());
            }
        }
        if (status != LedgerTransactionStatus.DRAFT && debitTotal != creditTotal) {
            throw new UnbalancedJournalException(
                    "posted transaction must be balanced: " + debitTotal + " vs " + creditTotal);
        }
    }

    /** 검증된 Journal을 POSTED 원장 거래로 확정합니다. */
    public static LedgerTransaction post(LedgerTransactionId id, Journal journal, Instant now) {
        List<LedgerEntry> entries = journal.lines().stream()
                .map(line -> new LedgerEntry(
                        LedgerEntryId.generate(), line.accountId(), line.direction(), line.money()))
                .toList();
        return new LedgerTransaction(
                id,
                journal.referenceType(),
                journal.referenceId(),
                journal.transactionType(),
                journal.currency(),
                LedgerTransactionStatus.POSTED,
                null,
                journal.effectiveAt(),
                now,
                entries);
    }

    public boolean isPosted() {
        return status == LedgerTransactionStatus.POSTED;
    }
}
