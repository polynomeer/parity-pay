package io.parity.pay.reconciliation.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.reconciliation.application.port.out.ReconciliationRepository;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch.ResolutionStatus;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.shared.security.ApprovalAuthority;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 불일치 해결과 보정 분개.
 *
 * <p>세 가지 규칙을 코드로 강제합니다.
 *
 * <ul>
 *   <li>보정은 기존 원장을 고치지 않고 새 분개를 만듭니다(ADR-009).
 *   <li>금액을 움직이는 보정은 요청자와 승인자가 달라야 합니다. 한 사람이 자기 요청을 승인할 수
 *       없습니다(docs/09-consistency-recovery.md §10, docs/05-technical-design.md §11).
 *   <li>사유와 근거(불일치 ID)가 없으면 보정할 수 없습니다.
 * </ul>
 */
@Service
public class MismatchResolutionService {

    private final ReconciliationRepository repository;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final ApprovalAuthority approvalAuthority;
    private final Clock clock;

    public MismatchResolutionService(
            ReconciliationRepository repository,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            ApprovalAuthority approvalAuthority,
            Clock clock) {
        this.repository = repository;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.approvalAuthority = approvalAuthority;
        this.clock = clock;
    }

    /** 미해결 불일치 목록입니다. 운영자가 무엇을 조사해야 하는지 보여줍니다. */
    @Transactional(readOnly = true)
    public java.util.List<ReconciliationMismatch> findOpen(
            io.parity.pay.reconciliation.domain.MismatchType type, int limit) {
        return repository.findOpen(type, limit);
    }

    /** 조사 결과 조치가 필요 없다고 판단한 경우입니다. 사유는 남습니다. */
    @Transactional
    public ReconciliationMismatch resolveWithoutAdjustment(
            UUID mismatchId, String operator, String reason, boolean ignored) {
        ReconciliationMismatch mismatch = load(mismatchId);
        requireOpen(mismatch);

        ReconciliationMismatch resolved = new ReconciliationMismatch(
                mismatch.mismatchId(),
                mismatch.runId(),
                mismatch.type(),
                mismatch.referenceType(),
                mismatch.referenceId(),
                mismatch.externalReferenceId(),
                mismatch.internalAmount(),
                mismatch.externalAmount(),
                mismatch.currency(),
                mismatch.detail(),
                ignored ? ResolutionStatus.IGNORED : ResolutionStatus.RESOLVED,
                ignored ? "IGNORED" : "NO_ADJUSTMENT_NEEDED",
                operator,
                reason,
                null,
                mismatch.detectedAt(),
                clock.instant());

        repository.resolve(resolved);
        return resolved;
    }

    /**
     * 보정 분개를 만들고 불일치를 해결 처리합니다.
     *
     * @param requestedBy 보정을 요청한 운영자
     * @param approvedBy 보정을 승인한 운영자. 요청자와 달라야 합니다.
     */
    @Transactional
    public ReconciliationMismatch resolveWithAdjustment(
            UUID mismatchId,
            String requestedBy,
            String approvedBy,
            String reason,
            AccountCode debitAccount,
            AccountCode creditAccount,
            UUID debitOwnerId,
            UUID creditOwnerId,
            long amount) {
        ReconciliationMismatch mismatch = load(mismatchId);
        requireOpen(mismatch);

        // 승인자가 실제로 존재하고, 승인 권한이 있으며, 요청자와 다른 사람인지 확인합니다.
        // 헤더에 아무 문자열이나 적어 자기 요청을 승인할 수 없습니다.
        approvalAuthority.requireDistinctApprover(requestedBy, approvedBy);
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "adjustment amount must be positive");
        }

        CurrencyCode currency = mismatch.currency() == null
                ? CurrencyCode.KRW
                : CurrencyCode.valueOf(mismatch.currency());

        LedgerAccount debit = resolveAccount(debitAccount, debitOwnerId, currency);
        LedgerAccount credit = resolveAccount(creditAccount, creditOwnerId, currency);

        LedgerTransaction adjustment = postJournal.post(JournalFactory.operationalAdjustment(
                mismatch.mismatchId(), debit.id(), credit.id(), Money.of(amount, currency),
                clock.instant()));

        ReconciliationMismatch resolved = new ReconciliationMismatch(
                mismatch.mismatchId(),
                mismatch.runId(),
                mismatch.type(),
                mismatch.referenceType(),
                mismatch.referenceId(),
                mismatch.externalReferenceId(),
                mismatch.internalAmount(),
                mismatch.externalAmount(),
                mismatch.currency(),
                mismatch.detail(),
                ResolutionStatus.RESOLVED,
                "ADJUSTMENT",
                requestedBy + " / approved by " + approvedBy,
                reason,
                adjustment.id(),
                mismatch.detectedAt(),
                clock.instant());

        repository.resolve(resolved);
        return resolved;
    }

    private LedgerAccount resolveAccount(AccountCode code, UUID ownerId, CurrencyCode currency) {
        return code.ownerType().ownerIdRequired()
                ? resolveLedgerAccount.resolve(code, requireOwner(code, ownerId), currency)
                : resolveLedgerAccount.resolveCorporate(code, currency);
    }

    private static UUID requireOwner(AccountCode code, UUID ownerId) {
        if (ownerId == null) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "account " + code + " requires an owner id");
        }
        return ownerId;
    }

    private void requireOpen(ReconciliationMismatch mismatch) {
        if (mismatch.resolutionStatus() != ResolutionStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "mismatch is already " + mismatch.resolutionStatus());
        }
    }

    private ReconciliationMismatch load(UUID mismatchId) {
        return repository
                .findById(mismatchId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "mismatch not found"));
    }
}
