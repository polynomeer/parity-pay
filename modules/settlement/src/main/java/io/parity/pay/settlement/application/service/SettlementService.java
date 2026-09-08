package io.parity.pay.settlement.application.service;

import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.settlement.application.event.SettlementEvents;
import io.parity.pay.settlement.application.port.in.SettlementQuery;
import io.parity.pay.settlement.application.port.in.SettlementView;
import io.parity.pay.settlement.application.port.out.SettlementItemRepository;
import io.parity.pay.settlement.application.port.out.SettlementRepository;
import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.settlement.domain.SettlementCalculator;
import io.parity.pay.settlement.domain.SettlementItem;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.CurrencyCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 계산.
 *
 * <p>항목을 모아 회차를 만들고, 그 안에서 수수료를 원장에 인식합니다(JE-007). 지급은 별도
 * 유스케이스입니다. 계산과 지급을 나누는 이유는 지급이 외부 호출을 포함하기 때문입니다.
 *
 * <p>근거: FR-016, docs/04-payment-policy.md §8, INV-008
 */
@Service
public class SettlementService implements SettlementQuery {

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository itemRepository;
    private final PostJournalUseCase postJournal;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final OutboxAppender outboxAppender;
    private final SettlementProperties properties;
    private final Clock clock;

    public SettlementService(
            SettlementRepository settlementRepository,
            SettlementItemRepository itemRepository,
            PostJournalUseCase postJournal,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            OutboxAppender outboxAppender,
            SettlementProperties properties,
            Clock clock) {
        this.settlementRepository = settlementRepository;
        this.itemRepository = itemRepository;
        this.postJournal = postJournal;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.outboxAppender = outboxAppender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 기간 안의 ELIGIBLE 항목을 모아 정산 회차를 만듭니다.
     *
     * <p>항목을 회차에 묶는 UPDATE는 {@code status = 'ELIGIBLE'} 조건부입니다. 두 배치가 동시에
     * 돌아도 같은 금액이 두 회차에 들어가지 않습니다. 근거: docs/04-payment-policy.md §8
     */
    @Transactional
    public SettlementView calculate(MerchantId merchantId, LocalDate periodStart, LocalDate periodEnd) {
        List<SettlementItem> items = itemRepository.findEligible(
                merchantId,
                periodEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1),
                properties.maxItemsPerSettlement());

        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "no eligible settlement items for the period");
        }

        Settlement settlement = SettlementCalculator.calculate(
                merchantId, periodStart, periodEnd, CurrencyCode.KRW, items, clock.instant());

        settlementRepository.save(settlement);
        itemRepository.assignToSettlement(items, settlement.id());

        // JE-007: 판매자에게 줄 의무 일부가 플랫폼 수수료 수익으로 확정됩니다.
        if (settlement.feeAmount().isPositive()) {
            LedgerAccount merchantPayable = resolveLedgerAccount.resolve(
                    AccountCode.MERCHANT_PAYABLE, merchantId.value(), settlement.currency());
            LedgerAccount feeRevenue =
                    resolveLedgerAccount.resolveCorporate(AccountCode.PLATFORM_FEE_REVENUE, settlement.currency());
            postJournal.post(JournalFactory.feeRecognized(
                    settlement.id(),
                    merchantPayable.id(),
                    feeRevenue.id(),
                    settlement.feeAmount(),
                    settlement.createdAt()));
        }

        outboxAppender.append(SettlementEvents.settlementCreated(settlement, settlement.createdAt()));
        return SettlementView.of(settlement);
    }

    @Transactional
    public SettlementView hold(SettlementId settlementId, String reason) {
        Settlement settlement = load(settlementId).hold(reason, clock.instant());
        settlementRepository.save(settlement);
        return SettlementView.of(settlement);
    }

    @Transactional
    public SettlementView release(SettlementId settlementId) {
        Settlement settlement = load(settlementId).release(clock.instant());
        settlementRepository.save(settlement);
        return SettlementView.of(settlement);
    }

    @Transactional(readOnly = true)
    @Override
    public SettlementView get(SettlementId settlementId) {
        return SettlementView.of(load(settlementId));
    }

    @Transactional(readOnly = true)
    @Override
    public List<SettlementView> listByMerchant(MerchantId merchantId, int limit) {
        return settlementRepository.findByMerchant(merchantId, limit).stream()
                .map(SettlementView::of)
                .toList();
    }

    /** 정산 항목 상세입니다. INV-008(항목 합 = 헤더 순액) 검증에 사용합니다. */
    @Transactional(readOnly = true)
    public List<SettlementItem> itemsOf(SettlementId settlementId) {
        return itemRepository.findBySettlementId(settlementId);
    }

    Settlement load(SettlementId settlementId) {
        return settlementRepository
                .findById(settlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "settlement not found"));
    }
}
