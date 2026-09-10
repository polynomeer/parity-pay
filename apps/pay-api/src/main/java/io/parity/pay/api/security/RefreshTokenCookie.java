package io.parity.pay.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰을 담는 쿠키.
 *
 * <p>토큰을 응답 본문에 넣지 않고 여기에 담습니다. {@code HttpOnly}라서 자바스크립트가 읽을 수
 * 없고, 그것이 이 클래스의 존재 이유 전부입니다 — 스크립트가 뚫려도 리프레시 토큰은 나가지
 * 않습니다. 근거: ADR-010
 *
 * <p>{@code SameSite=Lax}가 CSRF를 막습니다. 교차 사이트 POST에는 쿠키가 실리지 않으므로 별도
 * CSRF 토큰을 두지 않습니다. 그 대신 <b>API와 화면이 같은 사이트여야 한다</b>는 배포 제약이
 * 생깁니다(ADR-010 Consequences).
 */
@Component
public class RefreshTokenCookie {

    /** 인증 경로 밖으로는 나가지 않습니다. 다른 API 요청에 실릴 이유가 없습니다. */
    static final String PATH = "/api/v1/auth";

    static final String NAME = "paritypay_refresh";

    private final SecurityProperties properties;

    RefreshTokenCookie(SecurityProperties properties) {
        this.properties = properties;
    }

    /** 새 리프레시 토큰을 심습니다. 회전할 때마다 다시 심습니다. */
    public String issue(String refreshToken) {
        return build(refreshToken, properties.refreshTokenTtl()).toString();
    }

    /**
     * 쿠키를 지웁니다.
     *
     * <p>서버에서 철회하는 것만으로는 브라우저에 죽은 쿠키가 남습니다. 다음 재발급이 그것을 들고
     * 와서 401을 받으면 사용자는 이유를 알 수 없습니다.
     */
    public String clear() {
        return build("", Duration.ZERO).toString();
    }

    /** 요청이 들고 온 리프레시 토큰입니다. 없으면 비어 있습니다. */
    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookieSecure())
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
