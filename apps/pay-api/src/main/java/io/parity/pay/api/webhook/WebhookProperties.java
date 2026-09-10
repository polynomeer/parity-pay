package io.parity.pay.api.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 웹훅 수신 설정.
 *
 * <p>{@code secret}은 기관과 나눠 가진 값입니다. 주입하지 않으면 <b>애플리케이션이 뜨지
 * 않습니다.</b> 기본값을 두면 배포가 알려진 비밀값으로 조용히 뜨고, 그러면 아무나 웹훅에 서명할
 * 수 있습니다. 로컬용 값은 {@code local} 프로필에만 있습니다. 근거: ADR-011, docs/05 §13
 *
 * <p>{@code tolerance}는 서명된 시각의 허용 오차입니다. 좁을수록 재전송 창이 작아지지만, 기관과 우리
 * 시계 차이·네트워크 지연보다 좁으면 정상 웹훅이 거절됩니다.
 */
@ConfigurationProperties(prefix = "paritypay.webhook")
public record WebhookProperties(String secret, Duration tolerance) {

    public WebhookProperties {
        tolerance = tolerance == null ? Duration.ofMinutes(5) : tolerance;
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("paritypay.webhook.secret must be provided");
        }
    }
}
