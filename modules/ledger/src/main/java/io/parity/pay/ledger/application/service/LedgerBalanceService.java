package io.parity.pay.ledger.application.service;

import io.parity.pay.ledger.application.port.in.LedgerBalanceQuery;
import io.parity.pay.ledger.application.port.out.LedgerAccountRepository;
import io.parity.pay.ledger.application.port.out.LedgerTransactionRepository;
import io.parity.pay.ledger.domain.Direction;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원장 항목을 재생해 계정 잔액을 계산합니다.
 *
 * <p>계정의 정상 잔액 방향을 기준으로 계산합니다. 사용자 페이머니(2010)는 Credit 정상 잔액이므로
 * {@code sum(CREDIT) - sum(DEBIT)}입니다. 근거: docs/07-ledger-journal-catalog.md §6
 */
@Service
public class LedgerBalanceService implements LedgerBalanceQuery {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;

    public LedgerBalanceService(
            LedgerTransactionRepository transactionRepository, LedgerAccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Money balanceOf(LedgerAccountId accountId) {
        LedgerAccount account = accountRepository
                .findById(accountId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "ledger account not found: " + accountId));

        LedgerTransactionRepository.DebitCreditTotals totals = transactionRepository.totalsOf(accountId);
        long balance = account.normalBalance() == Direction.DEBIT
                ? totals.debitTotal() - totals.creditTotal()
                : totals.creditTotal() - totals.debitTotal();

        if (balance < 0) {
            // 정상 잔액 방향의 반대로 잔액이 생기는 것은 설계상 허용하지 않는 상태입니다.
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "ledger balance is negative for account " + account.code() + ": " + balance);
        }
        return Money.of(balance, account.currency());
    }
}
