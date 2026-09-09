package io.parity.pay.api.webhook;

/**
 * 기관이 보내는 웹훅 본문.
 *
 * <p>{@code sequence}는 기관이 업무 키별로 붙이는 증가 번호입니다. 이것이 없으면 순서가 뒤바뀐
 * 웹훅을 가려낼 방법이 없습니다 — 도착 시각은 순서를 말해주지 않고, 기관이 적은 시각은 재전송 때
 * 그대로 옵니다.
 *
 * <p>결과 필드({@code eventType})는 받아 두지만 <b>확정에 쓰지 않습니다</b>. 이유는
 * {@link PgWebhookService} javadoc에 있습니다.
 */
public record PgWebhookPayload(String eventId, String eventType, String externalKey, long sequence, Long amount) {

    /** 환불 알림인지입니다. 결제와 취소는 다른 Aggregate이므로 확정 경로가 다릅니다. */
    public boolean isRefund() {
        return eventType != null && eventType.startsWith("REFUND");
    }
}
