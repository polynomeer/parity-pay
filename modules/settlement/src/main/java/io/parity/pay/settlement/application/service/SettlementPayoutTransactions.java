package io.parity.pay.settlement.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.settlement.application.event.SettlementEvents;
import io.parity.pay.settlement.application.port.out.SettlementRepository;
import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.SettlementId;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지급의 트랜잭션 경계.
 *
 * <p>외부 호출은 이 클래스 밖에서 합니다. 각 메서드는 하나의 로컬 트랜잭션이며 상태·원장·이벤트가
 * 함께 커밋됩니다. 근거: docs/05-technical-design.md §7
 */
@Component
class SettlementPayoutTransactions {

    private final SettlementRepository settlementRepository;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    SettlementPayoutTransactions(
            SettlementRepository settlementRepository,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.settlementRepository = settlementRepository;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    /** 외부 호출 전에 PAYING을 커밋합니다. 여기서 죽어도 복구 작업이 조회로 확정할 수 있습니다. */
    @Transactional
    Settlement beginPayout(SettlementId settlementId) {
        Settlement settlement = load(settlementId).beginPayout(clock.instant());
        settlementRepository.save(settlement);
        return settlement;
    }

    @Transactional
    Settlement completePayout(SettlementId settlementId, String externalReferenceId) {
        Settlement current = load(settlementId);
        if (current.isPaid()) {
            return current;
        }

        Settlement paid = current.completePayout(externalReferenceId, clock.instant());
        settlementRepository.save(paid);

        // JE-008: 판매자 지급 의무가 사라지고 법인 은행 자산이 줄어듭니다.
        LedgerAccount merchantPayable = resolveLedgerAccount.resolve(
                AccountCode.MERCHANT_PAYABLE, paid.merchantId().value(), paid.currency());
        LedgerAccount bankDeposit = resolveLedgerAccount.resolveCorporate(AccountCode.BANK_DEPOSIT, paid.currency());
        postJournal.post(JournalFactory.settlementPaid(
                paid.id(), merchantPayable.id(), bankDeposit.id(), paid.netAmount(), paid.paidAt()));

        outboxAppender.append(SettlementEvents.settlementPaid(paid, paid.paidAt()));
        return paid;
    }

    @Transactional
    Settlement failPayout(SettlementId settlementId, String reason) {
        Settlement current = load(settlementId);
        if (current.isPaid()) {
            return current;
        }
        Settlement failed = current.failPayout(reason, clock.instant());
        settlementRepository.save(failed);
        return failed;
    }

    @Transactional
    Settlement markUnknown(SettlementId settlementId) {
        Settlement current = load(settlementId);
        if (current.isPaid()) {
            return current;
        }
        Settlement unknown = current.markUnknown(clock.instant());
        settlementRepository.save(unknown);
        return unknown;
    }

    private Settlement load(SettlementId settlementId) {
        return settlementRepository
                .findById(settlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "settlement not found"));
    }
}
