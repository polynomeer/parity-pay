package io.parity.pay.reconciliation.application.service;

import io.parity.pay.reconciliation.application.event.ReconciliationEvents;
import io.parity.pay.reconciliation.application.port.out.ReconciliationRepository;
import io.parity.pay.reconciliation.application.port.out.ReconciliationSourcePort;
import io.parity.pay.reconciliation.domain.ReconciliationMatcher;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import io.parity.pay.shared.event.OutboxAppender;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ReconciliationRepository repository;
    private final OutboxAppender outboxAppender;
    private final ReconciliationProperties properties;
    private final Clock clock;

    public ReconciliationService(
            ReconciliationSourcePort sourcePort,
            ReconciliationRepository repository,
            OutboxAppender outboxAppender,
            ReconciliationProperties properties,
            Clock clock) {
        this.sourcePort = sourcePort;
        this.repository = repository;
        this.outboxAppender = outboxAppender;
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

    @Transactional
    public ReconciliationSummary runTopUpReconciliation() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        return run(
                "TOP_UP",
                sourcePort.loadInternalTopUps(windowStart, now),
                sourcePort.loadExternalWithdrawals(windowStart, now),
                windowStart,
                now);
    }

    @Transactional
    public ReconciliationSummary runPayoutReconciliation() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        return run(
                "SETTLEMENT_PAYOUT",
                sourcePort.loadInternalPayouts(windowStart, now),
                sourcePort.loadExternalPayouts(windowStart, now),
                windowStart,
                now);
    }

    private ReconciliationSummary run(
            String runType,
            List<InternalRecord> internalRecords,
            List<ExternalRecord> externalRecords,
            Instant windowStart,
            Instant now) {
        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();

        List<ReconciliationMismatch> mismatches =
                ReconciliationMatcher.match(runId, internalRecords, externalRecords, properties.delayTolerance(), now);

        // 불일치가 실행 기록을 참조하므로 실행을 먼저 남깁니다. 건수는 처리 후 갱신합니다.
        repository.saveRun(
                runId,
                runType,
                windowStart,
                now,
                internalRecords.size(),
                externalRecords.size(),
                0,
                startedAt,
                clock.instant());

        int opened = 0;
        for (ReconciliationMismatch mismatch : mismatches) {
            if (!repository.appendIfAbsent(mismatch)) {
                // 이미 열려 있는 같은 차이입니다. 같은 문제를 매 대사마다 다시 올리지 않습니다.
                continue;
            }
            opened++;
            outboxAppender.append(ReconciliationEvents.mismatchDetected(mismatch));
            log.warn(
                    "reconciliation mismatch {} for {} {}",
                    mismatch.type(),
                    mismatch.referenceType(),
                    mismatch.referenceId());
        }

        repository.updateRunMismatchCount(runId, opened);

        return new ReconciliationSummary(runId, internalRecords.size(), externalRecords.size(), opened);
    }

    public record ReconciliationSummary(UUID runId, int internalCount, int externalCount, int mismatchCount) {}
}
