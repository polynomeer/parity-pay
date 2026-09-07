package io.parity.pay.wallet.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.idempotency.IdempotencyRecord;
import io.parity.pay.shared.idempotency.IdempotencyStatus;
import io.parity.pay.shared.idempotency.IdempotencyStore;
import io.parity.pay.wallet.application.event.WalletEvents;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.out.TopUpRepository;
import io.parity.pay.wallet.application.port.out.WalletBalanceRepository;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.domain.TopUp;
import io.parity.pay.wallet.domain.Wallet;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 충전의 트랜잭션 경계를 담당합니다.
 *
 * <p>외부 호출은 이 클래스 밖에서 수행합니다. 여기의 각 메서드는 하나의 로컬 트랜잭션이며, 그 안에서
 * 업무 상태·원장·잔액 스냅샷·멱등 기록이 함께 커밋됩니다.
 * 근거: docs/05-technical-design.md §7, docs/09-consistency-recovery.md §2
 */
@Component
class TopUpTransactions {

    static final String OPERATION = "TOP_UP";

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final TopUpRepository topUpRepository;
    private final IdempotencyStore idempotencyStore;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    TopUpTransactions(
            WalletRepository walletRepository,
            WalletBalanceRepository walletBalanceRepository,
            TopUpRepository topUpRepository,
            IdempotencyStore idempotencyStore,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.walletRepository = walletRepository;
        this.walletBalanceRepository = walletBalanceRepository;
        this.topUpRepository = topUpRepository;
        this.idempotencyStore = idempotencyStore;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    /**
     * 멱등 키를 선점하고 충전을 PROCESSING으로 만듭니다.
     *
     * @return 이번에 새로 시작한 충전이면 {@link Started#isNew()}가 true입니다. 이미 진행·완료된
     *     요청이면 기존 충전을 그대로 돌려줍니다.
     */
    @Transactional
    Started begin(TopUpCommand command, String requestHash) {
        Wallet wallet = walletRepository
                .findById(command.walletId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "wallet not found"));
        wallet.requireOwnedBy(command.memberId());
        wallet.requireTopUpAllowed();
        wallet.requireCurrency(command.amount().currency());

        IdempotencyRecord record = idempotencyStore.beginOrGet(
                command.memberId().value(), OPERATION, command.idempotencyKey(), requestHash);

        if (!record.requestHash().equals(requestHash)) {
            // 같은 키·다른 본문. 근거: docs/04-payment-policy.md §5
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "the same Idempotency-Key was used with a different request body");
        }

        Optional<TopUp> existing = record.businessReference().map(TopUpId::of).flatMap(topUpRepository::findById);
        if (existing.isPresent()) {
            return new Started(existing.get(), false);
        }

        TopUp topUp = TopUp.request(
                        command.walletId(),
                        command.bankAccountId(),
                        command.amount(),
                        command.idempotencyKey(),
                        clock.instant())
                .begin();
        TopUp saved = topUpRepository.save(topUp);

        // 업무 ID를 멱등 기록에 즉시 연결합니다. 응답 생성 실패로 금융 거래를 잃지 않기 위한 최소 정보입니다.
        idempotencyStore.settle(
                command.memberId().value(),
                OPERATION,
                command.idempotencyKey(),
                IdempotencyStatus.PROCESSING,
                saved.id().value());

        return new Started(saved, true);
    }

    /**
     * 외부 출금 성공을 확정합니다.
     *
     * <p>충전 상태, 충전 분개(JE-001), 잔액 스냅샷, 멱등 기록이 하나의 트랜잭션에서 커밋됩니다.
     */
    @Transactional
    TopUp completeSucceeded(MemberId memberId, TopUp topUp, String externalReferenceId) {
        TopUp current = reload(topUp);
        if (current.isSucceeded()) {
            return current;
        }

        TopUp succeeded = current.succeed(externalReferenceId, clock.instant());
        topUpRepository.save(succeeded);

        LedgerAccount bankDeposit = resolveLedgerAccount.resolveCorporate(
                AccountCode.BANK_DEPOSIT, succeeded.requestedAmount().currency());
        LedgerAccount userPayMoney = resolveLedgerAccount.resolve(
                AccountCode.USER_PAY_MONEY,
                succeeded.walletId().value(),
                succeeded.requestedAmount().currency());

        LedgerTransaction ledgerTransaction = postJournal.post(JournalFactory.topUpCompleted(
                succeeded.id(),
                bankDeposit.id(),
                userPayMoney.id(),
                succeeded.requestedAmount(),
                succeeded.completedAt()));

        int updated = walletBalanceRepository.increaseAvailable(succeeded.walletId(), succeeded.requestedAmount());
        if (updated != 1) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "wallet balance row missing for " + succeeded.walletId());
        }

        // 이벤트도 같은 트랜잭션에 기록합니다. 커밋되면 발행 의도가 남고, 롤백되면 함께 사라집니다.
        // 근거: ADR-005
        outboxAppender.append(WalletEvents.topUpCompleted(succeeded, ledgerTransaction.id()));

        idempotencyStore.settle(
                memberId.value(),
                OPERATION,
                succeeded.idempotencyKey(),
                IdempotencyStatus.COMPLETED,
                succeeded.id().value());
        return succeeded;
    }

    /** 외부가 명시적으로 실패를 응답했습니다. 금융 효과는 없습니다. */
    @Transactional
    TopUp completeFailed(MemberId memberId, TopUp topUp, String reason) {
        TopUp current = reload(topUp);
        if (current.status().isFinal()) {
            return current;
        }
        TopUp failed = current.fail(reason, clock.instant());
        topUpRepository.save(failed);
        idempotencyStore.settle(
                memberId.value(),
                OPERATION,
                failed.idempotencyKey(),
                IdempotencyStatus.FAILED,
                failed.id().value());
        return failed;
    }

    /**
     * 외부 결과가 불명확합니다. 실패로 확정하지 않고 복구 대상으로 남깁니다.
     * 근거: ADR-007, docs/09-consistency-recovery.md §7
     */
    @Transactional
    TopUp markUnknown(MemberId memberId, TopUp topUp, String externalReferenceId) {
        TopUp current = reload(topUp);
        if (current.status().isFinal()) {
            return current;
        }
        TopUp unknown = current.markUnknown(externalReferenceId);
        topUpRepository.save(unknown);
        idempotencyStore.settle(
                memberId.value(),
                OPERATION,
                unknown.idempotencyKey(),
                IdempotencyStatus.RECOVERY_REQUIRED,
                unknown.id().value());
        return unknown;
    }

    private TopUp reload(TopUp topUp) {
        return topUpRepository
                .findById(topUp.id())
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "top-up not found: " + topUp.id()));
    }

    record Started(TopUp topUp, boolean isNew) {}
}
