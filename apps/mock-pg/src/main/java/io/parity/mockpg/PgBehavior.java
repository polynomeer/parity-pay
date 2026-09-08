package io.parity.mockpg;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 이 PG가 어떻게 응답할지 정합니다.
 *
 * <p>승인과 환불을 따로 제어합니다. 승인은 되는데 환불만 막히는 상황은 실제로 흔하고, 그때 남는
 * 것은 "고객은 청구됐는데 돌려주지 못한 상태"라 대응이 다릅니다(F-007).
 */
@Component
public class PgBehavior {

    public enum Mode {
        NORMAL,
        /** 명시적으로 거절합니다. 결과를 아는 실패입니다. */
        EXPLICIT_DECLINE,
        /** 처리 전에 응답을 끊습니다. 청구·환불은 일어나지 않았습니다. */
        HANG_BEFORE_PROCESSING,
        /** 처리한 뒤 응답을 끊습니다. 돈은 이미 움직였습니다(F-006·F-007). */
        HANG_AFTER_PROCESSING
    }

    private volatile Mode approvalMode = Mode.NORMAL;
    private volatile Mode refundMode = Mode.NORMAL;
    private volatile boolean approvalStatusQueryAvailable = true;
    private volatile boolean refundStatusQueryAvailable = true;
    private volatile Duration hangFor = Duration.ofSeconds(30);

    public Mode approvalMode() {
        return approvalMode;
    }

    public void setApprovalMode(Mode mode) {
        this.approvalMode = mode;
    }

    public Mode refundMode() {
        return refundMode;
    }

    public void setRefundMode(Mode mode) {
        this.refundMode = mode;
    }

    public boolean approvalStatusQueryAvailable() {
        return approvalStatusQueryAvailable;
    }

    public void setApprovalStatusQueryAvailable(boolean available) {
        this.approvalStatusQueryAvailable = available;
    }

    public boolean refundStatusQueryAvailable() {
        return refundStatusQueryAvailable;
    }

    public void setRefundStatusQueryAvailable(boolean available) {
        this.refundStatusQueryAvailable = available;
    }

    public void setHangFor(Duration hangFor) {
        this.hangFor = hangFor;
    }

    /** 응답을 붙잡습니다. 호출자의 읽기 타임아웃보다 길면 타임아웃이 재현됩니다. */
    public void hang() {
        try {
            Thread.sleep(hangFor.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void reset() {
        approvalMode = Mode.NORMAL;
        refundMode = Mode.NORMAL;
        approvalStatusQueryAvailable = true;
        refundStatusQueryAvailable = true;
        hangFor = Duration.ofSeconds(30);
    }
}
