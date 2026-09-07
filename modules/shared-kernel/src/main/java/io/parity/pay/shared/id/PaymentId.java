package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record PaymentId(UUID value) implements TypedId {

    public PaymentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static PaymentId generate() {
        return new PaymentId(UuidV7.generate());
    }

    public static PaymentId of(UUID value) {
        return new PaymentId(value);
    }

    public static PaymentId of(String value) {
        return new PaymentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
