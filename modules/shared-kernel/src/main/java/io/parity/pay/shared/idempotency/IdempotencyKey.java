package io.parity.pay.shared.idempotency;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 클라이언트가 보내는 멱등 키.
 *
 * <p>길이와 문자 집합을 제한해 로그·DB에 예상치 못한 값이 들어오는 것을 막습니다. 키 자체를 원문으로
 * 로그에 남기지 않습니다(해시를 남깁니다). 근거: docs/04-payment-policy.md §5,
 * docs/05-technical-design.md §12
 */
public record IdempotencyKey(String value) {

    private static final int MAX_LENGTH = 100;
    private static final int MIN_LENGTH = 8;
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_.:@-]+$");

    public IdempotencyKey {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key length must be between " + MIN_LENGTH + " and " + MAX_LENGTH);
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key contains unsupported characters");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    /** 로그·응답에 원문 대신 사용할 마스킹 값입니다. */
    public String masked() {
        return value.substring(0, 4) + "***";
    }

    @Override
    public String toString() {
        return masked();
    }
}
