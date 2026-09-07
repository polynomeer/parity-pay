package io.parity.pay.ledger.application.port.out;

import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.ledger.domain.ReferenceType;
import io.parity.pay.ledger.domain.TransactionType;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.LedgerTransactionId;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository {

    Optional<LedgerTransaction> findByReference(
            ReferenceType referenceType, UUID referenceId, TransactionType transactionType);

    Optional<LedgerTransaction> findById(LedgerTransactionId id);

    LedgerTransaction save(LedgerTransaction transaction);

    /** 계정의 차변 합계와 대변 합계입니다. 잔액 계산과 INV-010 검증에 사용합니다. */
    DebitCreditTotals totalsOf(LedgerAccountId accountId);

    record DebitCreditTotals(long debitTotal, long creditTotal) {}
}
