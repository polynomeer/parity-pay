package io.parity.pay.reconciliation.application.service;

import io.parity.pay.reconciliation.application.event.ReconciliationEvents;
import io.parity.pay.reconciliation.application.port.out.ReconciliationRepository;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사 한 회차의 트랜잭션 경계.
 *
 * <p>{@link ReconciliationService}에서 분리한 이유는 <b>외부 호출을 트랜잭션 밖으로 빼기 위해서</b>
 * 입니다. 기관 명세를 HTTP로 받게 되면서, 예전처럼 한 메서드에 두면 DB 트랜잭션을 연 채로 네트워크
 * 응답을 기다리게 됩니다. 기관이 느린 날 커넥션이 그만큼 묶입니다.
 *
 * <p>같은 빈 안에서 호출하면 프록시를 거치지 않아 트랜잭션이 열리지 않으므로 빈을 나눕니다.
 *
 * <p>근거: CLAUDE.md(트랜잭션 안 외부 호출 금지), docs/09-consistency-recovery.md §10
 */
@Component
public class ReconciliationTransactions {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTransactions.class);

    private final ReconciliationRepository repository;
    private final OutboxAppender outboxAppender;
    private final ReconciliationProperties properties;
    private final Clock clock;

    public ReconciliationTransactions(
            ReconciliationRepository repository,
            OutboxAppender outboxAppender,
            ReconciliationProperties properties,
            Clock clock) {
        this.repository = repository;
        this.outboxAppender = outboxAppender;
        this.properties = properties;
        this.clock = clock;
    }

    /** 이미 받아 둔 기록을 비교하고 결과를 기록합니다. 여기서는 바깥을 부르지 않습니다. */
    @Transactional
    public ReconciliationService.ReconciliationSummary record(
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

        return new ReconciliationService.ReconciliationSummary(
                runId, internalRecords.size(), externalRecords.size(), opened);
    }
}
