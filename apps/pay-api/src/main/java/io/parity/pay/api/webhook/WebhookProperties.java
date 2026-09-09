package io.parity.pay.api.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 웹훅 수신 설정.
 *
 * <p>{@code secret}은 기관과 나눠 가진 값입니다. 운영에서는 환경변수로 주입합니다.
 *
 * <p>{@code tolerance}는 서명된 시각의 허용 오차입니다. 좁을수록 재전송 창이 작아지지만, 기관과 우리
 * 시계 차이·네트워크 지연보다 좁으면 정상 웹훅이 거절됩니다.
 */
@ConfigurationProperties(prefix = "paritypay.webhook")
public record WebhookProperties(String secret, Duration tolerance) {

    public WebhookProperties {
        tolerance = tolerance == null ? Duration.ofMinutes(5) : tolerance;
    }
}
