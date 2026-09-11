package io.parity.pay.wallet.application.service;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.wallet.application.port.out.BankWithdrawalPort;
import io.parity.pay.wallet.application.port.out.BankWithdrawalPort.WithdrawalStatus;
import io.parity.pay.wallet.application.port.out.TopUpRecoveryRepository;
import io.parity.pay.wallet.application.port.out.TopUpRecoveryRepository.PendingRecovery;
import io.parity.pay.wallet.application.port.out.TopUpRepository;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.domain.TopUp;
import io.parity.pay.wallet.domain.Wallet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 미확정 충전 복구 작업.
 *
 * <p>결과를 모르는 거래를 실패로 덮지 않고, 외부 상태 조회로 최종 상태에 수렴시킵니다.
 * 근거: ADR-007, docs/09-consistency-recovery.md §7·§8
 *
 * <p>복구 규칙:
 *
 * <ul>
 *   <li>같은 승인 요청을 다시 보내지 않고 조회를 먼저 합니다. 외부 멱등성이 불완전하면 재요청은
 *       중복 출금이 됩니다.
 *   <li>조회가 성공/실패를 알려주면 그대로 확정합니다. 확정 경로는 정상 흐름과 같은 트랜잭션
 *       메서드를 재사용하므로 원장·잔액·이벤트가 함께 커밋되고, 이미 확정된 건은 그대로 통과합니다.
 *   <li>외부에 기록이 "없음"으로 연속 확인되면 자금이 움직이지 않았다고 보고 실패로 확정합니다.
 *   <li>조회 자체가 실패하면 아무것도 확정하지 않고 백오프 후 다시 시도합니다.
 *   <li>제한 시간을 넘겨도 불명확하면 자동 재시도를 멈추고 운영자·대사 대상으로 넘깁니다.
 * </ul>
 */
@Service
public class TopUpRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TopUpRecoveryService.class);

    private final TopUpRecoveryRepository recoveryRepository;
    private final TopUpRepository topUpRepository;
    private final WalletRepository walletRepository;
    private final BankWithdrawalPort bankWithdrawalPort;
    private final TopUpTransactions transactions;
    private final TopUpRecoveryProperties properties;
    private final Clock clock;

    public TopUpRecoveryService(
            TopUpRecoveryRepository recoveryRepository,
            TopUpRepository topUpRepository,
            WalletRepository walletRepository,
            BankWithdrawalPort bankWithdrawalPort,
            TopUpTransactions transactions,
            TopUpRecoveryProperties properties,
            Clock clock) {
        this.recoveryRepository = recoveryRepository;
        this.topUpRepository = topUpRepository;
        this.walletRepository = walletRepository;
        this.bankWithdrawalPort = bankWithdrawalPort;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paritypay.recovery.top-up.interval-ms:5000}")
    void resolveScheduled() {
        if (!properties.enabled()) {
            return;
        }
        try {
            drainDue();
        } catch (RuntimeException e) {
            log.error("top-up recovery round failed", e);
        }
    }

    /**
     * 적체가 남아 있는 동안 배치를 이어서 처리하고, 확정된 건수의 합을 돌려줍니다.
     *
     * <p>배치가 가득 찼다는 것은 적체가 더 있다는 뜻입니다. 다음 틱을 기다리지 않고 이어서
     * 처리합니다. 틱당 한 배치면 기계·기관이 아무리 빨라도 초당 10건이 상한이고, 그 위에서는
     * 클라이언트가 90초 안에 답을 받지 못합니다(M-012). Outbox 발행기와 같은 판단입니다.
     * 기관이 죽어 있으면 배치 하나에 읽기 타임아웃 × 50이 걸리는데, 그것은 전과 같습니다. 이
     * 루프는 배치 사이의 5초 공백을 없앨 뿐이고, 한 틱의 길이는 maxRoundsPerTick이 막습니다.
     */
    public int drainDue() {
        int settled = 0;
        for (int round = 0; round < properties.maxRoundsPerTick(); round++) {
            Round result = resolveBatch();
            settled += result.settled();
            if (result.claimed() < properties.batchSize()) {
                break;
            }
        }
        return settled;
    }

    /**
     * 복구 대상을 한 배치 처리하고 최종 상태로 확정된 건수를 돌려줍니다.
     *
     * <p>선점(트랜잭션 1) → 외부 조회(트랜잭션 밖) → 확정(건별 트랜잭션) 순서입니다. 외부 호출을
     * 트랜잭션 안에서 하지 않습니다. 근거: docs/09-consistency-recovery.md §4
     */
    public int resolveDue() {
        return resolveBatch().settled();
    }

    /** 한 배치의 결과입니다. 선점한 건수가 배치 크기와 같으면 적체가 더 남아 있습니다. */
    private record Round(int claimed, int settled) {}

    private Round resolveBatch() {
        Instant now = clock.instant();
        List<PendingRecovery> pending = recoveryRepository.claimDue(
                now, now.minus(properties.grace()), now.plus(properties.lease()), properties.batchSize());

        int settled = 0;
        for (PendingRecovery item : pending) {
            if (resolveOne(item)) {
                settled++;
            }
        }
        return new Round(pending.size(), settled);
    }

    /** 운영자가 특정 건을 즉시 재조회하도록 요청할 때 사용합니다. */
    public RecoveryOutcome resolveNow(TopUpId topUpId) {
        TopUp topUp = loadTopUp(topUpId);
        if (topUp.status().isFinal()) {
            return new RecoveryOutcome(topUpId, topUp.status().name(), false, "already final");
        }
        PendingRecovery item = new PendingRecovery(topUpId, topUp.status(), 0, 0, false);
        boolean changed = resolveOne(item);
        return new RecoveryOutcome(
                topUpId, loadTopUp(topUpId).status().name(), changed, changed ? "settled" : "still unknown");
    }

    private boolean resolveOne(PendingRecovery item) {
        TopUp topUp = loadTopUp(item.topUpId());
        if (topUp.status().isFinal()) {
            recoveryRepository.clear(item.topUpId());
            return false;
        }

        MemberId memberId = ownerOf(topUp);
        WithdrawalStatus status;
        try {
            status = bankWithdrawalPort.getStatus(topUp.id());
        } catch (RuntimeException e) {
            log.warn("status query failed for top-up {}", topUp.id(), e);
            status = WithdrawalStatus.UNAVAILABLE;
        }

        return switch (status) {
            case SUCCEEDED -> {
                transactions.completeSucceeded(memberId, topUp, topUp.id().toString());
                recoveryRepository.clear(topUp.id());
                log.info("recovered top-up {} as SUCCEEDED", topUp.id());
                yield true;
            }
            case FAILED -> {
                transactions.completeFailed(memberId, topUp, "EXTERNAL_DECLINED");
                recoveryRepository.clear(topUp.id());
                log.info("recovered top-up {} as FAILED", topUp.id());
                yield true;
            }
            case NOT_FOUND -> handleNotFound(item, topUp, memberId);
            case UNAVAILABLE -> {
                scheduleRetryOrEscalate(item, "status query unavailable");
                yield false;
            }
        };
    }

    /**
     * 외부에 요청 기록이 없습니다.
     *
     * <p>한 번의 "없음"으로 실패를 확정하지 않습니다. 외부가 요청을 받고 기록하기 직전일 수도 있기
     * 때문입니다. 연속으로 확인된 횟수가 기준을 넘으면 자금이 움직이지 않았다고 보고 실패로
     * 확정합니다. 근거: docs/09-consistency-recovery.md §7
     */
    private boolean handleNotFound(PendingRecovery item, TopUp topUp, MemberId memberId) {
        int notFoundCount = item.notFoundCount() + 1;
        if (notFoundCount >= properties.notFoundConfirmThreshold()) {
            transactions.completeFailed(memberId, topUp, "EXTERNAL_RECORD_NOT_FOUND");
            recoveryRepository.clear(topUp.id());
            log.info("settled top-up {} as FAILED after {} consecutive not-found results", topUp.id(), notFoundCount);
            return true;
        }
        int attemptCount = item.attemptCount() + 1;
        recoveryRepository.scheduleRetry(
                topUp.id(),
                attemptCount,
                notFoundCount,
                clock.instant().plus(backoffFor(attemptCount)),
                "external record not found",
                clock.instant());
        return false;
    }

    private void scheduleRetryOrEscalate(PendingRecovery item, String reason) {
        int attemptCount = item.attemptCount() + 1;
        if (attemptCount >= properties.maxAttempts()) {
            // 자동으로 안전하게 확정할 수 없습니다. 계속 외부를 두드리는 대신 사람에게 넘깁니다.
            recoveryRepository.markManualReview(item.topUpId(), attemptCount, reason, clock.instant());
            log.warn("top-up {} needs manual review after {} attempts: {}", item.topUpId(), attemptCount, reason);
            return;
        }
        recoveryRepository.scheduleRetry(
                item.topUpId(),
                attemptCount,
                item.notFoundCount(),
                clock.instant().plus(backoffFor(attemptCount)),
                reason,
                clock.instant());
    }

    /** 지수 백오프에 jitter를 더합니다. 여러 인스턴스가 같은 시각에 외부를 두드리지 않게 합니다. */
    private Duration backoffFor(int attempt) {
        long base = properties.baseBackoff().toMillis() * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(base, properties.maxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(capped / 2 + jitter);
    }

    private TopUp loadTopUp(TopUpId topUpId) {
        return topUpRepository
                .findById(topUpId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "top-up not found: " + topUpId));
    }

    private MemberId ownerOf(TopUp topUp) {
        Wallet wallet = walletRepository
                .findById(topUp.walletId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INTERNAL_ERROR, "wallet not found for top-up " + topUp.id()));
        return wallet.memberId();
    }

    /** 운영자 재조회 결과입니다. */
    public record RecoveryOutcome(TopUpId topUpId, String status, boolean changed, String detail) {}
}
