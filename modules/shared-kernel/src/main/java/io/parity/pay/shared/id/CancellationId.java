package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record CancellationId(UUID value) implements TypedId {

    public CancellationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static CancellationId generate() {
        return new CancellationId(UuidV7.generate());
    }

    public static CancellationId of(UUID value) {
        return new CancellationId(value);
    }

    public static CancellationId of(String value) {
        return new CancellationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
