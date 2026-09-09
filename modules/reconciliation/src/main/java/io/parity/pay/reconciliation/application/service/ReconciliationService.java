package io.parity.pay.reconciliation.application.service;

import io.parity.pay.reconciliation.application.port.out.ReconciliationSourcePort;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 대사 실행.
 *
 * <p>내부 기록과 외부기관 기록을 비교해 차이를 찾습니다. 차이를 찾는 것까지가 이 서비스의 일이며,
 * 금액을 고치지 않습니다. 보정은 근거와 승인을 갖춘 별도 작업입니다.
 * 근거: docs/04-payment-policy.md §9, docs/09-consistency-recovery.md §10
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationSourcePort sourcePort;
    private final ReconciliationTransactions transactions;
    private final ReconciliationProperties properties;
    private final Clock clock;

    public ReconciliationService(
            ReconciliationSourcePort sourcePort,
            ReconciliationTransactions transactions,
            ReconciliationProperties properties,
            Clock clock) {
        this.sourcePort = sourcePort;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${paritypay.reconciliation.cron:0 0 4 * * *}")
    void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("scheduled reconciliation failed", e);
        }
    }

    /** 충전과 정산 지급을 모두 대사합니다. */
    public ReconciliationSummary runAll() {
        ReconciliationSummary topUps = runTopUpReconciliation();
        ReconciliationSummary payouts = runPayoutReconciliation();
        return new ReconciliationSummary(
                topUps.runId(),
                topUps.internalCount() + payouts.internalCount(),
                topUps.externalCount() + payouts.externalCount(),
                topUps.mismatchCount() + payouts.mismatchCount());
    }

    /**
     * 충전 대사입니다.
     *
     * <p>기록을 먼저 다 받고 나서 트랜잭션을 엽니다. 기관 명세는 HTTP로 오므로, 한 메서드 안에서
     * 하면 트랜잭션을 연 채 네트워크 응답을 기다리게 됩니다.
     *
     * <p>명세를 받지 못하면 {@code ReconciliationSourceUnavailableException}이 그대로 올라가고 이
     * 회차는 아무것도 기록하지 않습니다. 빈 명세로 진행하면 우리 쪽 기록 전부가 불일치가 됩니다.
     */
    public ReconciliationSummary runTopUpReconciliation() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        List<InternalRecord> internal = sourcePort.loadInternalTopUps(windowStart, now);
        List<ExternalRecord> external = sourcePort.loadExternalWithdrawals(windowStart, now);
        return transactions.record("TOP_UP", internal, external, windowStart, now);
    }

    /** 정산 지급 대사입니다. 충전과 같은 규칙이며 대상만 다릅니다. */
    public ReconciliationSummary runPayoutReconciliation() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        List<InternalRecord> internal = sourcePort.loadInternalPayouts(windowStart, now);
        List<ExternalRecord> external = sourcePort.loadExternalPayouts(windowStart, now);
        return transactions.record("SETTLEMENT_PAYOUT", internal, external, windowStart, now);
    }

    public record ReconciliationSummary(UUID runId, int internalCount, int externalCount, int mismatchCount) {}
}
