package io.parity.pay.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재설정 메일에 담을 링크.
 *
 * <p>{@code linkTemplate}의 {@code {token}} 자리에 토큰이 들어갑니다. 화면의 주소를 서버가 알아야
 * 하는 유일한 자리라 설정으로 둡니다 — 앱은 자기 오리진만 알고 서버는 앱의 오리진을 모르기
 * 때문입니다(ADR-011).
 */
@ConfigurationProperties(prefix = "paritypay.password-reset")
public record PasswordResetLinkProperties(String linkTemplate, String from) {

    public PasswordResetLinkProperties {
        from = from == null || from.isBlank() ? "no-reply@paritypay.local" : from;
        if (linkTemplate != null && !linkTemplate.contains("{token}")) {
            throw new IllegalStateException("paritypay.password-reset.link-template must contain {token}");
        }
    }

    public String linkFor(String rawToken) {
        if (linkTemplate == null) {
            throw new IllegalStateException("paritypay.password-reset.link-template is not configured");
        }
        return linkTemplate.replace("{token}", rawToken);
    }
}
