package io.parity.pay.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    @DisplayName("허용 문자와 길이만 통과한다")
    void validatesFormat() {
        assertThat(IdempotencyKey.of("top-up-2026-09-05-0001").value()).isEqualTo("top-up-2026-09-05-0001");

        assertThatThrownBy(() -> IdempotencyKey.of("short")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("has space in it")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("x".repeat(101))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("로그에 원문이 남지 않도록 마스킹한다")
    void masksValueInToString() {
        IdempotencyKey key = IdempotencyKey.of("secret-key-value");

        assertThat(key.toString()).isEqualTo("secr***").doesNotContain("secret-key-value");
    }
}
