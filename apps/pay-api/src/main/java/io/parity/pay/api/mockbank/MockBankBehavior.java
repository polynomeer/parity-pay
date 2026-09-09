package io.parity.pay.api.mockbank;

import org.springframework.stereotype.Component;

/**
 * 외부 기관의 동작을 원격으로 제어합니다.
 *
 * <p>이전에는 같은 프로세스의 스위치였습니다. 지금은 기관이 다른 프로세스에 있으므로, 이 클래스는
 * 스위치가 아니라 **기관에게 보내는 지시**입니다. 표면은 그대로 두었습니다 — 호출하는 쪽이 알아야
 * 할 것은 "기관이 이렇게 행동한다"이지 그것이 어떻게 전달되는지가 아닙니다.
 *
 * <p>모드 이름도 그대로 둡니다. 기관 쪽 이름(HANG_*)은 기관의 어휘이고, 우리 쪽 이름(TIMEOUT_*)은
 * 우리가 겪는 현상입니다. 같은 사건을 부르는 두 이름이며, 그 번역이 이 클래스의 일입니다.
 *
 * <p>근거: docs/05-technical-design.md §10, docs/10-test-strategy.md §6
 */
@Component
public class MockBankBehavior {

    public enum Mode {
        /** 정상 성공 또는 잔액 부족에 따른 명시적 실패. */
        NORMAL,
        /** 외부가 명시적으로 실패를 응답합니다. */
        EXPLICIT_FAILURE,
        /** 출금 전에 응답이 오지 않습니다. 실제 읽기 타임아웃이 납니다. */
        TIMEOUT_BEFORE_WITHDRAWAL,
        /** 출금·지급을 처리한 뒤 응답이 오지 않습니다(F-006, F-010). */
        TIMEOUT_AFTER_WITHDRAWAL;

        /** 기관 쪽 어휘로 옮깁니다. */
        String remoteName() {
            return switch (this) {
                case NORMAL -> "NORMAL";
                case EXPLICIT_FAILURE -> "EXPLICIT_FAILURE";
                case TIMEOUT_BEFORE_WITHDRAWAL -> "HANG_BEFORE_PROCESSING";
                case TIMEOUT_AFTER_WITHDRAWAL -> "HANG_AFTER_PROCESSING";
            };
        }
    }

    private final MockBankClient client;
    private final MockBankProperties properties;

    MockBankBehavior(MockBankClient client, MockBankProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void setMode(Mode mode) {
        send(new MockBankClient.BehaviorRequest(mode.remoteName(), null, null, null, null, hangMillis(), null));
    }

    public void setPayoutMode(Mode mode) {
        send(new MockBankClient.BehaviorRequest(null, mode.remoteName(), null, null, null, hangMillis(), null));
    }

    public void setStatusQueryAvailable(boolean available) {
        send(new MockBankClient.BehaviorRequest(null, null, available, null, null, null, null));
    }

    public void setPayoutStatusQueryAvailable(boolean available) {
        send(new MockBankClient.BehaviorRequest(null, null, null, available, null, null, null));
    }

    /**
     * 대사 명세를 내줄 수 있는지 바꿉니다.
     *
     * <p>건별 조회 가용성과 따로입니다. 실제로 둘은 다른 시스템이고, 명세만 못 받는 날이 대사에는
     * 더 위험합니다 — 기관에 기록이 하나도 없는 것처럼 보이기 때문입니다.
     */
    public void setStatementAvailable(boolean available) {
        send(new MockBankClient.BehaviorRequest(null, null, null, null, available, null, null));
    }

    /**
     * 장애 모드만 되돌립니다. <b>장부는 건드리지 않습니다.</b>
     *
     * <p>시험이 장애를 주입했다가 정상으로 되돌릴 때 부르는 자리입니다. 여기서 장부까지 비우면
     * 방금 만든 외부 기록이 사라져, 복구가 조회로 확정해야 할 대상이 없어집니다.
     */
    public void reset() {
        send(new MockBankClient.BehaviorRequest(null, null, null, null, null, null, true));
    }

    /**
     * 장부까지 비웁니다. 시험 사이의 초기화 전용입니다.
     *
     * <p>기관이 자기 데이터베이스를 갖기 전에는 시험이 우리 JdbcTemplate으로 기관 표를 비웠습니다.
     * 이제 그럴 수 없고, 그것이 옳습니다 — 실제 기관의 장부를 우리가 지울 수는 없습니다.
     */
    public void resetLedgerAndBehavior() {
        client.resetInstitution();
    }

    /**
     * 기관이 응답을 붙잡고 있을 시간입니다.
     *
     * <p>읽기 타임아웃보다 넉넉히 길어야 타임아웃이 재현됩니다. 너무 길면 기관의 요청 스레드가
     * 그만큼 묶이므로 두 배로 잡습니다.
     */
    private long hangMillis() {
        return properties.readTimeout().toMillis() * 2;
    }

    private void send(MockBankClient.BehaviorRequest request) {
        client.setBehavior(request);
    }
}
