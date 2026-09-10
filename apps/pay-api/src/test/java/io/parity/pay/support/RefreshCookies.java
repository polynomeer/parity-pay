package io.parity.pay.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * 리프레시 쿠키를 시험에서 다루는 도우미. 근거: ADR-010
 *
 * <p>토큰이 응답 본문에서 사라졌으므로, 회전·철회를 확인하는 시험은 브라우저가 하는 일을 손으로
 * 해야 합니다 — {@code Set-Cookie}를 받아 두었다가 {@code Cookie}로 돌려보내는 것입니다.
 */
public final class RefreshCookies {

    public static final String NAME = "paritypay_refresh";

    private RefreshCookies() {}

    /** 응답이 심은 리프레시 쿠키의 {@code Set-Cookie} 줄 전체입니다. 속성까지 들어 있습니다. */
    public static String setCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("응답에 Set-Cookie가 없습니다").isNotNull();
        return cookies.stream()
                .filter(cookie -> cookie.startsWith(NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("리프레시 쿠키가 없습니다: " + cookies));
    }

    /** 브라우저가 다음 요청에 실어 보내는 형태({@code 이름=값})입니다. */
    public static String value(ResponseEntity<?> response) {
        return setCookie(response).split(";", 2)[0];
    }

    /** 쿠키를 들고 가는 요청 헤더입니다. */
    public static HttpHeaders carrying(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }
}
