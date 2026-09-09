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

    /**
     * 웹훅을 어떻게 보낼지입니다. 실제 기관의 재전송과 순서 뒤바뀜을 재현합니다.
     *
     * <p>이것이 없으면 수신 쪽의 중복·역순 방어를 시험할 방법이 우리가 손으로 같은 요청을 두 번
     * 보내는 것뿐입니다. 그건 기관이 실제로 하는 일과 다릅니다 — 재전송은 <b>같은 eventId</b>로
     * 오고, 역순은 <b>다른 eventId에 더 작은 순번</b>으로 옵니다.
     */
    public enum WebhookMode {
        NORMAL,
        /** 같은 알림을 두 번 보냅니다(재전송). */
        DUPLICATE,
        /** 새 알림 뒤에 오래된 알림을 보냅니다(역순 도착). */
        OUT_OF_ORDER,
        /** 보내지 않습니다. 조회만으로 확정되는지 보는 데 씁니다. */
        NONE
    }

    private volatile Mode approvalMode = Mode.NORMAL;
    private volatile Mode refundMode = Mode.NORMAL;
    private volatile boolean approvalStatusQueryAvailable = true;
    private volatile boolean refundStatusQueryAvailable = true;
    private volatile Duration hangFor = Duration.ofSeconds(30);

    private volatile WebhookMode webhookMode = WebhookMode.NORMAL;

    /**
     * 결과를 알릴 주소입니다.
     *
     * <p>실제 PG도 가맹점이 콜백 주소를 등록합니다. 설정 파일이 아니라 여기 두는 이유는
     * 시험에서 상대의 포트가 실행할 때마다 달라지기 때문입니다.
     */
    private volatile String webhookUrl;

    private final String defaultWebhookUrl;

    PgBehavior(@org.springframework.beans.factory.annotation.Value("${mock-pg.webhook.url:}") String url) {
        this.defaultWebhookUrl = url;
        this.webhookUrl = url;
    }

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

    public WebhookMode webhookMode() {
        return webhookMode;
    }

    public void setWebhookMode(WebhookMode mode) {
        this.webhookMode = mode;
    }

    public String webhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String url) {
        this.webhookUrl = url;
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
        webhookMode = WebhookMode.NORMAL;
        webhookUrl = defaultWebhookUrl;
    }
}
