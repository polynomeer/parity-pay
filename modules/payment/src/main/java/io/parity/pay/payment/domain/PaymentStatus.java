package io.parity.pay.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 결제 상태와 허용 전이. 근거: docs/06-domain-state-design.md §4
 *
 * <p>{@code UNKNOWN}은 실패가 아니라 외부 결과 확인이 필요한 상태입니다. 페이머니 결제는 외부 호출이
 * 없어 이 상태로 가지 않지만, 외부 PG 결제를 추가할 때 쓰기 위해 전이표에 남겨둡니다.
 */
public enum PaymentStatus {
    READY,
    PROCESSING,
    APPROVED,
    PARTIALLY_CANCELED,
    CANCELED,
    FAILED,
    UNKNOWN;

    private static final Set<PaymentStatus> FINAL_STATES = EnumSet.of(CANCELED, FAILED);
    private static final Set<PaymentStatus> CANCELLABLE = EnumSet.of(APPROVED, PARTIALLY_CANCELED);

    public boolean isFinal() {
        return FINAL_STATES.contains(this);
    }

    /** 취소를 요청할 수 있는 상태입니다. 근거: docs/04-payment-policy.md §6 */
    public boolean isCancellable() {
        return CANCELLABLE.contains(this);
    }

    boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == PROCESSING;
            case PROCESSING -> next == APPROVED || next == FAILED || next == UNKNOWN;
            case UNKNOWN -> next == APPROVED || next == FAILED;
            case APPROVED -> next == PARTIALLY_CANCELED || next == CANCELED;
                // 부분 취소가 반복되는 동안에는 같은 상태에 머무릅니다.
            case PARTIALLY_CANCELED -> next == PARTIALLY_CANCELED || next == CANCELED;
            case CANCELED, FAILED -> false;
        };
    }
}
