package io.parity.pay.wallet.application.service;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.idempotency.RequestHasher;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.out.BankWithdrawalPort;
import io.parity.pay.wallet.application.port.out.BankWithdrawalPort.BankWithdrawalResult;
import io.parity.pay.wallet.application.port.out.TopUpRepository;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.domain.TopUp;
import io.parity.pay.wallet.domain.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 충전 유스케이스 오케스트레이션.
 *
 * <p>외부 은행 호출은 어떤 DB 트랜잭션에도 속하지 않습니다. 로컬 상태 변경은
 * {@link TopUpTransactions}의 트랜잭션 메서드에서만 일어납니다.
 * 근거: docs/05-technical-design.md §7, docs/09-consistency-recovery.md §4
 */
@Service
public class TopUpService implements RequestTopUpUseCase {

    private static final Logger log = LoggerFactory.getLogger(TopUpService.class);

    private final TopUpTransactions transactions;
    private final BankWithdrawalPort bankWithdrawalPort;
    private final TopUpRepository topUpRepository;
    private final WalletRepository walletRepository;

    public TopUpService(
            TopUpTransactions transactions,
            BankWithdrawalPort bankWithdrawalPort,
            TopUpRepository topUpRepository,
            WalletRepository walletRepository) {
        this.transactions = transactions;
        this.bankWithdrawalPort = bankWithdrawalPort;
        this.topUpRepository = topUpRepository;
        this.walletRepository = walletRepository;
    }

    @Override
    public TopUpView requestTopUp(TopUpCommand command) {
        String requestHash = canonicalHash(command);

        TopUpTransactions.Started started = transactions.begin(command, requestHash);
        if (!started.isNew()) {
            // 같은 키의 재요청입니다. 외부에 다시 요청하지 않고 기존 결과를 그대로 돌려줍니다.
            log.info(
                    "idempotent replay for top-up {} (status={})",
                    started.topUp().id(),
                    started.topUp().status());
            return TopUpView.of(started.topUp());
        }

        TopUp topUp = started.topUp();
        BankWithdrawalResult result;
        try {
            result = bankWithdrawalPort.withdraw(
                    topUp.bankAccountId(), topUp.requestedAmount(), topUp.id());
        } catch (RuntimeException e) {
            // 예외를 실패로 단정하지 않습니다. 결과를 모르는 상태로 보존합니다. 근거: ADR-007
            log.warn("bank withdrawal outcome is unknown for top-up {}", topUp.id(), e);
            result = BankWithdrawalResult.unknown(null);
        }

        TopUp settled = switch (result.outcome()) {
            case SUCCEEDED -> transactions.completeSucceeded(
                    command.memberId(), topUp, result.externalReferenceId());
            case FAILED -> transactions.completeFailed(
                    command.memberId(), topUp, result.failureReason());
            case UNKNOWN -> transactions.markUnknown(
                    command.memberId(), topUp, result.externalReferenceId());
        };
        return TopUpView.of(settled);
    }

    @Override
    public TopUpView getTopUp(MemberId memberId, TopUpId topUpId) {
        TopUp topUp = topUpRepository
                .findById(topUpId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "top-up not found"));
        Wallet wallet = walletRepository
                .findById(topUp.walletId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "wallet not found"));
        wallet.requireOwnedBy(memberId);
        return TopUpView.of(topUp);
    }

    /**
     * 요청 본문의 정규화 해시입니다. 필드 순서와 표현을 고정해 같은 요청이 같은 해시를 갖게 합니다.
     * 멱등 키 원문은 해시 대상에 포함하지 않습니다(키는 이미 조회 키입니다).
     */
    private static String canonicalHash(TopUpCommand command) {
        String canonical = String.join(
                "|",
                command.memberId().toString(),
                command.walletId().toString(),
                command.bankAccountId().toString(),
                Long.toString(command.amount().amount()),
                command.amount().currency().name());
        return RequestHasher.sha256(canonical);
    }
}
