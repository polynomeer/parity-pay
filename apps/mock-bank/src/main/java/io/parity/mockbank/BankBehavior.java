package io.parity.mockbank;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 이 기관이 어떻게 응답할지 정합니다.
 *
 * <p>같은 프로세스에 있을 때는 예외를 던져 타임아웃을 흉내 냈습니다. 이제는 실제로 **응답을 늦추거나
 * 아예 하지 않을 수 있습니다.** 클라이언트는 진짜 읽기 타임아웃을 겪습니다.
 *
 * <p>운영 환경에 존재하면 안 되는 기능이며, 이 앱 자체가 운영에 배포되지 않습니다.
 */
@Component
public class BankBehavior {

    public enum Mode {
        /** 정상 처리. */
        NORMAL,
        /** 명시적 실패를 응답합니다. 결과를 아는 실패입니다. */
        EXPLICIT_FAILURE,
        /** 처리하기 전에 응답을 끊습니다. 자금은 움직이지 않았습니다. */
        HANG_BEFORE_PROCESSING,
        /** 처리한 뒤 응답을 끊습니다. 자금은 움직였습니다(F-006·F-010). */
        HANG_AFTER_PROCESSING
    }

    private volatile Mode withdrawalMode = Mode.NORMAL;
    private volatile Mode payoutMode = Mode.NORMAL;
    private volatile boolean withdrawalStatusQueryAvailable = true;
    private volatile boolean payoutStatusQueryAvailable = true;

    /**
     * 대사 명세를 내줄 수 있는지입니다.
     *
     * <p>건별 조회 장애(F-009)와 스위치를 따로 둡니다. 실제로 "건별 조회는 되는데 일별 명세가 안
     * 나오는" 날이 있고, 그때 대사를 돌리면 기관에 기록이 하나도 없는 것처럼 보입니다.
     */
    private volatile boolean statementAvailable = true;

    /**
     * 응답을 끊을 때 얼마나 붙잡고 있을지입니다.
     *
     * <p>즉시 끊으면 클라이언트는 연결 오류를 받고, 이것은 "요청이 전달되지 않았다"로 오해되기
     * 쉽습니다. 실제 타임아웃은 기관이 응답 없이 붙잡고 있는 것이므로 그대로 재현합니다.
     */
    private volatile Duration hangFor = Duration.ofSeconds(30);

    public Mode withdrawalMode() {
        return withdrawalMode;
    }

    public void setWithdrawalMode(Mode mode) {
        this.withdrawalMode = mode;
    }

    public Mode payoutMode() {
        return payoutMode;
    }

    public void setPayoutMode(Mode mode) {
        this.payoutMode = mode;
    }

    public boolean withdrawalStatusQueryAvailable() {
        return withdrawalStatusQueryAvailable;
    }

    public void setWithdrawalStatusQueryAvailable(boolean available) {
        this.withdrawalStatusQueryAvailable = available;
    }

    public boolean payoutStatusQueryAvailable() {
        return payoutStatusQueryAvailable;
    }

    public void setPayoutStatusQueryAvailable(boolean available) {
        this.payoutStatusQueryAvailable = available;
    }

    public boolean statementAvailable() {
        return statementAvailable;
    }

    public void setStatementAvailable(boolean available) {
        this.statementAvailable = available;
    }

    public Duration hangFor() {
        return hangFor;
    }

    public void setHangFor(Duration hangFor) {
        this.hangFor = hangFor;
    }

    /** 응답을 끊습니다. 클라이언트의 읽기 타임아웃보다 오래 붙잡고 있으면 됩니다. */
    public void hang() {
        try {
            Thread.sleep(hangFor.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void reset() {
        withdrawalMode = Mode.NORMAL;
        payoutMode = Mode.NORMAL;
        withdrawalStatusQueryAvailable = true;
        payoutStatusQueryAvailable = true;
        statementAvailable = true;
        hangFor = Duration.ofSeconds(30);
    }
}
