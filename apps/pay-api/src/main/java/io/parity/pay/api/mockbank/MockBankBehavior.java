package io.parity.pay.api.mockbank;

import org.springframework.stereotype.Component;

/**
 * Mock Bank의 응답 동작을 제어합니다.
 *
 * <p>장애 시나리오(F-001~F-010)를 재현하기 위한 주입 지점입니다. 운영 환경에서는 사용하지 않습니다.
 * 근거: docs/05-technical-design.md §10, docs/10-test-strategy.md §6
 */
@Component
public class MockBankBehavior {

    public enum Mode {
        /** 정상 성공 또는 잔액 부족에 따른 명시적 실패. */
        NORMAL,
        /** 외부가 명시적으로 실패를 응답합니다. */
        EXPLICIT_FAILURE,
        /** 출금을 처리하기 전에 응답이 끊깁니다. 내부는 결과를 알 수 없습니다. */
        TIMEOUT_BEFORE_WITHDRAWAL,
        /** 출금·지급을 처리한 뒤 응답이 유실됩니다. 승인 후 응답 유실 시나리오(F-006, F-010)입니다. */
        TIMEOUT_AFTER_WITHDRAWAL
    }

    private volatile Mode mode = Mode.NORMAL;

    /**
     * 상태 조회 API의 가용성입니다. 출금 동작과 독립적으로 제어합니다.
     *
     * <p>승인 결과를 모르는 상태에서 조회까지 실패하는 상황(F-009)을 재현하기 위해 필요합니다.
     * 근거: docs/05-technical-design.md §10, docs/10-test-strategy.md §6
     */
    private volatile boolean statusQueryAvailable = true;

    /** 판매자 지급의 동작입니다. 충전 출금과 독립적으로 제어합니다. */
    private volatile Mode payoutMode = Mode.NORMAL;

    private volatile boolean payoutStatusQueryAvailable = true;

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

    public Mode payoutMode() {
        return payoutMode;
    }

    public void setPayoutMode(Mode payoutMode) {
        this.payoutMode = payoutMode;
    }

    public boolean payoutStatusQueryAvailable() {
        return payoutStatusQueryAvailable;
    }

    public void setPayoutStatusQueryAvailable(boolean payoutStatusQueryAvailable) {
        this.payoutStatusQueryAvailable = payoutStatusQueryAvailable;
    }

    public void reset() {
        this.mode = Mode.NORMAL;
        this.statusQueryAvailable = true;
        this.payoutMode = Mode.NORMAL;
        this.payoutStatusQueryAvailable = true;
    }
}
