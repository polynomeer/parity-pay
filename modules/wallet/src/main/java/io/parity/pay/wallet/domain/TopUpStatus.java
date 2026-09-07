package io.parity.pay.wallet.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 충전 상태와 허용 전이. 근거: docs/06-domain-state-design.md §4
 *
 * <p>{@code UNKNOWN}은 실패가 아니라 외부 결과 확인이 필요한 상태입니다. 근거: ADR-007
 */
public enum TopUpStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    private static final Set<TopUpStatus> FINAL_STATES = EnumSet.of(SUCCEEDED, FAILED);

    public boolean isFinal() {
        return FINAL_STATES.contains(this);
    }

    boolean canTransitionTo(TopUpStatus next) {
        return switch (this) {
            case REQUESTED -> next == PROCESSING;
            case PROCESSING -> next == SUCCEEDED || next == FAILED || next == UNKNOWN;
            case UNKNOWN -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
