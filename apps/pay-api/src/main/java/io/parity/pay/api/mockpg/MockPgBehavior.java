package io.parity.pay.api.mockpg;

import org.springframework.stereotype.Component;

/**
 * 외부 PG의 동작을 원격으로 제어합니다.
 *
 * <p>은행과 같습니다. 스위치가 아니라 **기관에게 보내는 지시**이며, 모드 이름을 우리 어휘
 * (TIMEOUT_*)에서 기관 어휘(HANG_*)로 옮기는 것이 이 클래스의 일입니다.
 *
 * <p>근거: docs/05-technical-design.md §10, docs/10-test-strategy.md §6
 */
@Component
public class MockPgBehavior {

    public enum Mode {
        NORMAL,
        EXPLICIT_DECLINE,
        /** 처리 전에 응답이 오지 않습니다. 실제 읽기 타임아웃이 납니다. */
        TIMEOUT_BEFORE_APPROVAL,
        /** 처리한 뒤 응답이 오지 않습니다(F-006·F-007). */
        TIMEOUT_AFTER_APPROVAL;

        String remoteName() {
            return switch (this) {
                case NORMAL -> "NORMAL";
                case EXPLICIT_DECLINE -> "EXPLICIT_DECLINE";
                case TIMEOUT_BEFORE_APPROVAL -> "HANG_BEFORE_PROCESSING";
                case TIMEOUT_AFTER_APPROVAL -> "HANG_AFTER_PROCESSING";
            };
        }
    }

    private final MockPgClient client;
    private final MockPgProperties properties;

    MockPgBehavior(MockPgClient client, MockPgProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 승인 동작입니다. */
    public void setMode(Mode mode) {
        client.setBehavior(
                new MockPgClient.BehaviorRequest(null, null, mode.remoteName(), null, null, null, hangMillis(), null));
    }

    /** 환불 동작입니다. 승인과 독립적으로 제어합니다. */
    public void setRefundMode(Mode mode) {
        client.setBehavior(
                new MockPgClient.BehaviorRequest(null, null, null, mode.remoteName(), null, null, hangMillis(), null));
    }

    public void setStatusQueryAvailable(boolean available) {
        client.setBehavior(new MockPgClient.BehaviorRequest(null, null, null, null, available, null, null, null));
    }

    public void setRefundStatusQueryAvailable(boolean available) {
        client.setBehavior(new MockPgClient.BehaviorRequest(null, null, null, null, null, available, null, null));
    }

    /**
     * 결과 알림(웹훅)을 어떻게 보낼지 정합니다.
     *
     * <p>기관이 실제로 하는 것을 재현합니다 — 재전송은 같은 eventId로 오고, 역순은 다른 eventId에
     * 더 작은 순번으로 옵니다. 우리가 손으로 같은 요청을 두 번 보내는 것과는 다릅니다.
     */
    public void setWebhookMode(String mode) {
        client.setBehavior(new MockPgClient.BehaviorRequest(mode, null, null, null, null, null, null, null));
    }

    /** 알림을 받을 주소를 등록합니다. 시험에서는 자기 포트가 실행할 때마다 달라집니다. */
    public void setWebhookUrl(String url) {
        client.setBehavior(new MockPgClient.BehaviorRequest(null, url, null, null, null, null, null, null));
    }

    /** 장애 모드만 되돌립니다. 장부는 건드리지 않습니다. 이유는 MockBankBehavior에 적어 두었습니다. */
    public void reset() {
        client.setBehavior(new MockPgClient.BehaviorRequest(null, null, null, null, null, null, null, true));
    }

    /** 장부까지 비웁니다. 시험 사이의 초기화 전용입니다. */
    public void resetLedgerAndBehavior() {
        client.resetInstitution();
    }

    /** 기관이 붙잡고 있을 시간입니다. 읽기 타임아웃의 두 배로 잡아 타임아웃이 확실히 재현되게 합니다. */
    private long hangMillis() {
        return properties.readTimeout().toMillis() * 2;
    }
}
