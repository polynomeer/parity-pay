package io.parity.pay.settlement.application.service;

import io.parity.pay.settlement.application.port.out.MerchantPayoutPort;
import io.parity.pay.settlement.application.port.out.MerchantPayoutPort.PayoutStatus;
import io.parity.pay.settlement.application.port.out.SettlementRecoveryRepository;
import io.parity.pay.settlement.application.port.out.SettlementRecoveryRepository.PendingPayoutRecovery;
import io.parity.pay.settlement.application.port.out.SettlementRepository;
import io.parity.pay.settlement.domain.Settlement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 지급 결과가 불명확한 정산의 복구.
 *
 * <p>충전 복구와 같은 규칙입니다. 재지급 대신 조회를 먼저 하고, 조회가 안 되면 아무것도 확정하지
 * 않으며, 제한을 넘기면 사람에게 넘깁니다. 근거: docs/09-consistency-recovery.md §7·§8
 */
@Service
public class SettlementRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SettlementRecoveryService.class);

    private final SettlementRecoveryRepository recoveryRepository;
    private final SettlementRepository settlementRepository;
    private final MerchantPayoutPort merchantPayoutPort;
    private final SettlementPayoutTransactions transactions;
    private final SettlementProperties properties;
    private final Clock clock;

    public SettlementRecoveryService(
            SettlementRecoveryRepository recoveryRepository,
            SettlementRepository settlementRepository,
            MerchantPayoutPort merchantPayoutPort,
            SettlementPayoutTransactions transactions,
            SettlementProperties properties,
            Clock clock) {
        this.recoveryRepository = recoveryRepository;
        this.settlementRepository = settlementRepository;
        this.merchantPayoutPort = merchantPayoutPort;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paritypay.settlement.recovery-interval-ms:5000}")
    void resolveScheduled() {
        if (!properties.recoveryEnabled()) {
            return;
        }
        try {
            resolveDue();
        } catch (RuntimeException e) {
            log.error("settlement payout recovery round failed", e);
        }
    }

    /** 미확정 지급을 조회로 확정합니다. 확정된 건수를 돌려줍니다. */
    public int resolveDue() {
        Instant now = clock.instant();
        List<PendingPayoutRecovery> pending =
                recoveryRepository.claimDue(now, now.plus(properties.recoveryLease()), 50);

        int settled = 0;
        for (PendingPayoutRecovery item : pending) {
            if (resolveOne(item)) {
                settled++;
            }
        }
        return settled;
    }

    private boolean resolveOne(PendingPayoutRecovery item) {
        Optional<Settlement> loaded = settlementRepository.findById(item.settlementId());
        if (loaded.isEmpty() || loaded.get().isPaid()) {
            recoveryRepository.clear(item.settlementId());
            return false;
        }

        PayoutStatus status;
        try {
            status = merchantPayoutPort.getStatus(item.settlementId());
        } catch (RuntimeException e) {
            log.warn("payout status query failed for settlement {}", item.settlementId(), e);
            status = PayoutStatus.UNAVAILABLE;
        }

        return switch (status) {
            case SUCCEEDED -> {
                transactions.completePayout(
                        item.settlementId(), item.settlementId().toString());
                recoveryRepository.clear(item.settlementId());
                log.info("recovered settlement {} as PAID", item.settlementId());
                yield true;
            }
            case FAILED -> {
                transactions.failPayout(item.settlementId(), "EXTERNAL_PAYOUT_DECLINED");
                recoveryRepository.clear(item.settlementId());
                yield true;
            }
            case NOT_FOUND -> handleNotFound(item);
            case UNAVAILABLE -> {
                scheduleRetryOrEscalate(item, "payout status query unavailable");
                yield false;
            }
        };
    }

    private boolean handleNotFound(PendingPayoutRecovery item) {
        int notFoundCount = item.notFoundCount() + 1;
        if (notFoundCount >= properties.recoveryNotFoundConfirmThreshold()) {
            // 외부에 지급 기록이 반복 확인되지 않았습니다. 돈이 나가지 않았다고 보고 실패로 확정하면
            // 운영자가 다시 지급을 시도할 수 있습니다.
            transactions.failPayout(item.settlementId(), "EXTERNAL_PAYOUT_RECORD_NOT_FOUND");
            recoveryRepository.clear(item.settlementId());
            return true;
        }
        recoveryRepository.scheduleRetry(
                item.settlementId(),
                item.attemptCount() + 1,
                notFoundCount,
                clock.instant().plus(backoffFor(item.attemptCount() + 1)),
                "payout record not found",
                clock.instant());
        return false;
    }

    private void scheduleRetryOrEscalate(PendingPayoutRecovery item, String reason) {
        int attemptCount = item.attemptCount() + 1;
        if (attemptCount >= properties.recoveryMaxAttempts()) {
            recoveryRepository.markManualReview(item.settlementId(), attemptCount, reason, clock.instant());
            log.warn("settlement {} needs manual review: {}", item.settlementId(), reason);
            return;
        }
        recoveryRepository.scheduleRetry(
                item.settlementId(),
                attemptCount,
                item.notFoundCount(),
                clock.instant().plus(backoffFor(attemptCount)),
                reason,
                clock.instant());
    }

    private Duration backoffFor(int attempt) {
        long base = properties.recoveryBaseBackoff().toMillis() * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(base, properties.recoveryMaxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(capped / 2 + jitter);
    }
}
