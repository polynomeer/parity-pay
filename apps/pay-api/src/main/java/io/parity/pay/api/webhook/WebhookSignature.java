package io.parity.pay.api.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 웹훅 서명 검증.
 *
 * <p>웹훅은 인증 없이 열려 있는 경로입니다. 누구나 우리에게 "그 결제 승인됐어요"라고 말할 수
 * 있으면 안 됩니다. 기관과 나눠 가진 비밀로 서명을 확인합니다.
 *
 * <p>서명 대상은 <b>시각과 본문 원문</b>입니다. 파싱한 뒤 다시 직렬화한 것에 서명하면 공백 하나가
 * 달라도 검증이 깨지고, 반대로 파서가 무시하는 필드를 공격자가 끼워 넣을 수 있습니다.
 *
 * <p>시각을 함께 서명하고 허용 오차를 두는 이유는 재전송 공격입니다. 서명은 그대로 유효하므로 오래된
 * 요청을 그대로 다시 보낼 수 있습니다. 다만 이것은 창을 좁히는 것일 뿐 중복 방지가 아닙니다 —
 * 중복은 {@code webhook_receipt}가 막습니다.
 *
 * <p>비교는 {@link MessageDigest#isEqual}로 합니다. 문자열 비교는 앞에서부터 다른 자리에서 멈추므로
 * 걸린 시간이 정답에 얼마나 가까운지를 알려줍니다.
 *
 * <p>근거: docs/05-technical-design.md §11, F-008
 */
@Component
public class WebhookSignature {

    private final WebhookProperties properties;
    private final Clock clock;

    WebhookSignature(WebhookProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 서명과 시각이 모두 맞으면 통과, 아니면 예외입니다. */
    public void verify(String timestamp, String signature, String rawBody) {
        if (timestamp == null || signature == null) {
            throw new WebhookRejectedException("missing signature headers");
        }
        Instant sentAt;
        try {
            sentAt = Instant.parse(timestamp);
        } catch (RuntimeException e) {
            throw new WebhookRejectedException("unparseable timestamp");
        }
        Duration skew = Duration.between(sentAt, clock.instant()).abs();
        if (skew.compareTo(properties.tolerance()) > 0) {
            throw new WebhookRejectedException("timestamp outside the accepted window");
        }
        String expected = sign(timestamp, rawBody);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new WebhookRejectedException("signature does not match");
        }
    }

    /** 기관이 붙이는 것과 같은 방식으로 서명을 만듭니다. 테스트와 Mock 기관이 함께 씁니다. */
    public String sign(String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign the webhook payload", e);
        }
    }
}
