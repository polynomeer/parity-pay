package io.parity.mockpg;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 이 기관이 보내는 웹훅.
 *
 * <p>실제 PG는 결과를 알려주려고 웹훅을 보냅니다. 그리고 **at-least-once**로 보냅니다 — 응답을 받지
 * 못하면 다시 보내고, 재시도가 원래 것보다 먼저 도착하기도 합니다. 그 두 가지를 재현할 수 있어야
 * 수신 쪽이 정말 견디는지 알 수 있습니다.
 *
 * <p>업무 키마다 증가하는 순번을 붙입니다. 도착 시각으로는 순서를 알 수 없고, 기관이 적은 시각은
 * 재전송 때 그대로 오기 때문입니다.
 *
 * <p>보내기는 실패해도 무시합니다. 웹훅이 도착하지 않아도 상대는 조회로 확정할 수 있어야 하고,
 * 실제 기관도 우리 응답을 기다리느라 자기 처리를 멈추지 않습니다.
 */
@Component
class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final PgBehavior behavior;
    private final Clock clock;
    private final RestClient restClient = RestClient.create();
    private final String secret;
    private final Map<String, AtomicLong> sequences = new java.util.concurrent.ConcurrentHashMap<>();

    WebhookSender(
            PgBehavior behavior,
            Clock clock,
            @Value("${mock-pg.webhook.secret:local-development-only-webhook-secret}") String secret) {
        this.behavior = behavior;
        this.clock = clock;
        this.secret = secret;
    }

    /**
     * 결과를 알립니다.
     *
     * <p>{@link PgBehavior.WebhookMode}에 따라 한 번 보내거나, 같은 것을 두 번 보내거나, 오래된 것을
     * 나중에 보냅니다. 마지막 것이 역순 재현입니다 — 순번 3을 보낸 뒤 순번 2를 보냅니다.
     */
    void notifyResult(String eventType, String externalKey, long amount) {
        String url = behavior.webhookUrl();
        if (url == null || url.isBlank() || behavior.webhookMode() == PgBehavior.WebhookMode.NONE) {
            return;
        }
        long sequence =
                sequences.computeIfAbsent(externalKey, key -> new AtomicLong()).incrementAndGet();
        String eventId = UUID.randomUUID().toString();
        send(eventId, eventType, externalKey, amount, sequence);

        switch (behavior.webhookMode()) {
            case DUPLICATE ->
            // 같은 eventId로 한 번 더 보냅니다. 재전송은 원문 그대로 오므로 eventId도 같습니다.
            send(eventId, eventType, externalKey, amount, sequence);
            case OUT_OF_ORDER -> {
                // 뒤늦게 도착한 이전 알림입니다. eventId는 다르고 순번이 더 작습니다.
                if (sequence > 1) {
                    send(UUID.randomUUID().toString(), eventType, externalKey, amount, sequence - 1);
                }
            }
            default -> {
                // NORMAL: 한 번만 보냅니다.
            }
        }
    }

    private void send(String eventId, String eventType, String externalKey, long amount, long sequence) {
        String body = "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"externalKey\":\"%s\",\"sequence\":%d,\"amount\":%d}"
                .formatted(eventId, eventType, externalKey, sequence, amount);
        String timestamp = Instant.now(clock).toString();
        try {
            restClient
                    .post()
                    .uri(behavior.webhookUrl())
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Signature", sign(timestamp, body))
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // 상대가 받지 못해도 우리 처리는 끝났습니다. 상대는 조회로 확정할 수 있습니다.
            log.warn("webhook delivery failed for {}: {}", externalKey, e.toString());
        }
    }

    private String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign the webhook", e);
        }
    }
}
