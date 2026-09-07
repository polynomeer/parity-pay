package io.parity.pay.wallet.application.port.out;

import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.money.Money;

/**
 * 외부 은행 출금 포트.
 *
 * <p>호출은 DB 트랜잭션 밖에서 이루어집니다. 결과는 성공·실패·불명확 셋 중 하나이며, 타임아웃은
 * 실패가 아니라 {@code UNKNOWN}입니다. 근거: docs/05-technical-design.md §7, ADR-007
 */
public interface BankWithdrawalPort {

    /**
     * @param externalIdempotencyKey 외부기관에 전달하는 비즈니스 멱등 키입니다. 같은 키로 재요청해도
     *     외부에서 중복 출금이 일어나지 않아야 합니다.
     */
    BankWithdrawalResult withdraw(
            BankAccountId bankAccountId, Money amount, TopUpId externalIdempotencyKey);

    /**
     * 외부기관에 남은 요청 결과를 조회합니다.
     *
     * <p>결과를 모를 때 같은 승인 요청을 다시 보내는 대신 이 조회를 먼저 합니다. 외부 멱등성이
     * 불완전하면 재요청은 중복 출금을 만들 수 있습니다. 근거: docs/09-consistency-recovery.md §7
     */
    WithdrawalStatus getStatus(TopUpId externalIdempotencyKey);

    enum WithdrawalStatus {
        /** 외부에 성공 기록이 있습니다. */
        SUCCEEDED,
        /** 외부에 실패 기록이 있습니다. */
        FAILED,
        /** 외부에 요청 기록 자체가 없습니다. 자금이 움직이지 않았을 가능성이 높습니다. */
        NOT_FOUND,
        /** 조회 자체가 실패했습니다. 결과에 대한 정보가 없습니다. */
        UNAVAILABLE
    }

    enum Outcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    record BankWithdrawalResult(Outcome outcome, String externalReferenceId, String failureReason) {

        public static BankWithdrawalResult succeeded(String externalReferenceId) {
            return new BankWithdrawalResult(Outcome.SUCCEEDED, externalReferenceId, null);
        }

        public static BankWithdrawalResult failed(String reason) {
            return new BankWithdrawalResult(Outcome.FAILED, null, reason);
        }

        public static BankWithdrawalResult unknown(String externalReferenceId) {
            return new BankWithdrawalResult(Outcome.UNKNOWN, externalReferenceId, null);
        }
    }
}
