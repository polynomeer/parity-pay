package io.parity.pay.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카드 PG 웹훅 수신.
 *
 * <p>인증 토큰 없이 열려 있는 유일한 쓰기 경로입니다. 기관은 우리 토큰을 갖고 있지 않으므로, 신원
 * 확인은 나눠 가진 비밀로 만든 서명으로 합니다.
 *
 * <p>본문을 문자열로 받는 이유는 서명 대상이 <b>원문</b>이기 때문입니다. 객체로 받아 다시
 * 직렬화하면 같은 뜻이라도 바이트가 달라집니다.
 *
 * <p>응답은 거의 언제나 200입니다. 중복이든, 오래된 것이든, 우리가 모르는 건이든 기관이 재전송할
 * 이유가 없습니다. 400·401은 우리가 처리를 시도조차 하지 않았다는 뜻이며, 그때만 기관이 다시
 * 보내야 합니다.
 *
 * <p>근거: F-008, docs/09-consistency-recovery.md §11
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class PgWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PgWebhookController.class);

    private final WebhookSignature signature;
    private final PgWebhookService webhookService;
    private final ObjectMapper objectMapper;

    PgWebhookController(WebhookSignature signature, PgWebhookService webhookService, ObjectMapper objectMapper) {
        this.signature = signature;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/mock-pg")
    ResponseEntity<WebhookAck> receive(
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String providedSignature,
            @RequestBody String rawBody) {
        try {
            signature.verify(timestamp, providedSignature, rawBody);
        } catch (WebhookRejectedException e) {
            // 왜 거절했는지는 답하지 않습니다. 알려주면 맞을 때까지 시도할 수 있습니다.
            log.warn("rejected a webhook: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PgWebhookPayload payload;
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            payload = new PgWebhookPayload(
                    text(node, "eventId"),
                    text(node, "eventType"),
                    text(node, "externalKey"),
                    node.path("sequence").asLong(),
                    node.hasNonNull("amount") ? node.get("amount").asLong() : null);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.badRequest().build();
        }
        if (payload.eventId() == null || payload.externalKey() == null) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(new WebhookAck(webhookService.handle(payload).name()));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** 무엇으로 처리했는지 알려 줍니다. 기관 쪽 로그에서 원인을 찾을 때 씁니다. */
    record WebhookAck(String result) {}
}
