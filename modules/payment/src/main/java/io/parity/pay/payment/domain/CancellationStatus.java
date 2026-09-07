package io.parity.pay.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/** 취소 상태와 허용 전이. 근거: docs/06-domain-state-design.md §4 */
public enum CancellationStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    UNKNOWN;

    private static final Set<CancellationStatus> FINAL_STATES = EnumSet.of(COMPLETED, FAILED);

    public boolean isFinal() {
        return FINAL_STATES.contains(this);
    }

    boolean canTransitionTo(CancellationStatus next) {
        return switch (this) {
            case REQUESTED -> next == PROCESSING;
            case PROCESSING -> next == COMPLETED || next == FAILED || next == UNKNOWN;
            case UNKNOWN -> next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
