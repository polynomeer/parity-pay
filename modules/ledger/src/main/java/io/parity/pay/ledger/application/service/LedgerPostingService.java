package io.parity.pay.ledger.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.out.LedgerAccountRepository;
import io.parity.pay.ledger.application.port.out.LedgerTransactionRepository;
import io.parity.pay.ledger.domain.Journal;
import io.parity.pay.ledger.domain.JournalLine;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.LedgerTransactionId;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전기 절차 구현. 근거: docs/07-ledger-journal-catalog.md §7
 *
 * <p>{@link Propagation#MANDATORY}를 사용해 업무 트랜잭션 밖에서 원장만 따로 기록되는 것을 막습니다.
 * 원장 전기는 언제나 업무 상태 변경과 같은 트랜잭션에 속합니다.
 * 근거: docs/05-technical-design.md §7, docs/09-consistency-recovery.md §2
 */
@Service
public class LedgerPostingService implements PostJournalUseCase {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;
    private final Clock clock;

    public LedgerPostingService(
            LedgerTransactionRepository transactionRepository, LedgerAccountRepository accountRepository, Clock clock) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(Journal journal) {
        // 1. 같은 업무 참조로 이미 전기된 거래가 있으면 새로 만들지 않습니다 (INV-004).
        Optional<LedgerTransaction> existing = transactionRepository.findByReference(
                journal.referenceType(), journal.referenceId(), journal.transactionType());
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2. 계정 상태와 통화를 검증합니다. Journal 생성 시 균형·양수·단일통화는 이미 검증되었습니다.
        for (JournalLine line : journal.lines()) {
            LedgerAccount account = accountRepository
                    .findById(line.accountId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.INTERNAL_ERROR, "ledger account not found: " + line.accountId()));
            if (!account.active()) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR, "ledger account is not active: " + account.code());
            }
            if (account.currency() != journal.currency()) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "account currency " + account.currency() + " differs from journal currency "
                                + journal.currency());
            }
        }

        // 3. POSTED 상태로 확정합니다. 이후 이 행은 수정·삭제하지 않습니다 (INV-006).
        LedgerTransaction posted = LedgerTransaction.post(LedgerTransactionId.generate(), journal, clock.instant());
        return transactionRepository.save(posted);
    }
}
