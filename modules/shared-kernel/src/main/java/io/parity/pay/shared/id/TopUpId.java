package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record TopUpId(UUID value) implements TypedId {

    public TopUpId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static TopUpId generate() {
        return new TopUpId(UuidV7.generate());
    }

    public static TopUpId of(UUID value) {
        return new TopUpId(value);
    }

    public static TopUpId of(String value) {
        return new TopUpId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
