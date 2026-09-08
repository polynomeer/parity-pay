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
        send(new MockBankClient.BehaviorRequest(mode.remoteName(), null, null, null, hangMillis(), null));
    }

    public void setPayoutMode(Mode mode) {
        send(new MockBankClient.BehaviorRequest(null, mode.remoteName(), null, null, hangMillis(), null));
    }

    public void setStatusQueryAvailable(boolean available) {
        send(new MockBankClient.BehaviorRequest(null, null, available, null, null, null));
    }

    public void setPayoutStatusQueryAvailable(boolean available) {
        send(new MockBankClient.BehaviorRequest(null, null, null, available, null, null));
    }

    public void reset() {
        send(new MockBankClient.BehaviorRequest(null, null, null, null, null, true));
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
