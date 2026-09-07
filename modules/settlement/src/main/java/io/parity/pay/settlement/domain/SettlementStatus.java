package io.parity.pay.settlement.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 정산 상태와 허용 전이. 근거: docs/06-domain-state-design.md §4
 *
 * <p>결제 승인 즉시 지급하지 않습니다. 구매확정으로 항목이 쌓이고, 계산이 끝난 뒤에야 지급합니다.
 * 근거: docs/04-payment-policy.md §8
 */
public enum SettlementStatus {
    /** 항목이 고정되어 지급할 금액이 확정된 상태입니다. */
    CALCULATED,
    /** 분쟁·위험·계좌 오류·법적 사유로 지급을 보류합니다. */
    HELD,
    PAYING,
    PAID,
    FAILED,
    /** 외부 지급 결과를 알 수 없습니다. 실패가 아닙니다. 근거: ADR-007 */
    UNKNOWN;

    private static final Set<SettlementStatus> FINAL_STATES = EnumSet.of(PAID);

    public boolean isFinal() {
        return FINAL_STATES.contains(this);
    }

    boolean canTransitionTo(SettlementStatus next) {
        return switch (this) {
            case CALCULATED -> next == PAYING || next == HELD;
            case HELD -> next == CALCULATED;
            case PAYING -> next == PAID || next == FAILED || next == UNKNOWN;
            // 실패한 지급은 다시 시도할 수 있습니다. 근거: docs/06-domain-state-design.md §4
            case FAILED -> next == PAYING;
            case UNKNOWN -> next == PAID || next == FAILED;
            case PAID -> false;
        };
    }
}
