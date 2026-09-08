package io.parity.pay.api.mockpg;

import org.springframework.stereotype.Component;

/**
 * Mock PG의 응답 동작을 제어합니다.
 *
 * <p>장애 시나리오(F-006·F-007·F-009)를 재현하기 위한 주입 지점입니다. 운영 환경에는 이 대역
 * 자체가 없습니다. 근거: docs/05-technical-design.md §10, docs/10-test-strategy.md §6
 */
@Component
public class MockPgBehavior {

    public enum Mode {
        /** 정상 승인. */
        NORMAL,
        /** 외부가 명시적으로 거절합니다. 결과를 아는 실패입니다. */
        EXPLICIT_DECLINE,
        /** 처리 전에 응답이 끊깁니다. 외부에서는 아무 일도 일어나지 않았습니다. */
        TIMEOUT_BEFORE_APPROVAL,
        /** 처리한 뒤 응답이 유실됩니다. 청구 또는 환불은 이미 일어났습니다(F-006·F-007). */
        TIMEOUT_AFTER_APPROVAL
    }

    private volatile Mode mode = Mode.NORMAL;

    /**
     * 상태 조회 API의 가용성입니다. 승인 동작과 독립적으로 제어합니다.
     *
     * <p>결과를 모르는 상태에서 조회까지 실패하는 상황(F-009)을 만들기 위해 필요합니다.
     */
    private volatile boolean statusQueryAvailable = true;

    /** 환불의 동작입니다. 승인과 독립적으로 제어합니다(F-007). */
    private volatile Mode refundMode = Mode.NORMAL;

    private volatile boolean refundStatusQueryAvailable = true;

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean statusQueryAvailable() {
        return statusQueryAvailable;
    }

    public void setStatusQueryAvailable(boolean statusQueryAvailable) {
        this.statusQueryAvailable = statusQueryAvailable;
    }

    public Mode refundMode() {
        return refundMode;
    }

    public void setRefundMode(Mode refundMode) {
        this.refundMode = refundMode;
    }

    public boolean refundStatusQueryAvailable() {
        return refundStatusQueryAvailable;
    }

    public void setRefundStatusQueryAvailable(boolean refundStatusQueryAvailable) {
        this.refundStatusQueryAvailable = refundStatusQueryAvailable;
    }

    public void reset() {
        this.mode = Mode.NORMAL;
        this.statusQueryAvailable = true;
        this.refundMode = Mode.NORMAL;
        this.refundStatusQueryAvailable = true;
    }
}
