package io.parity.pay.api.webhook;

/**
 * 받아들일 수 없는 웹훅입니다.
 *
 * <p>서명이 맞지 않거나 시각이 허용 범위를 벗어난 경우입니다. 왜 거절했는지는 응답에 담지 않습니다 —
 * 서명이 틀렸는지 시각이 틀렸는지 알려주면 맞을 때까지 시도할 수 있습니다.
 */
public class WebhookRejectedException extends RuntimeException {

    public WebhookRejectedException(String message) {
        super(message);
    }
}
