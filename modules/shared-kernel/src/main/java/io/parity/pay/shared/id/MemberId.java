package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record MemberId(UUID value) implements TypedId {

    public MemberId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static MemberId generate() {
        return new MemberId(UuidV7.generate());
    }

    public static MemberId of(UUID value) {
        return new MemberId(value);
    }

    public static MemberId of(String value) {
        return new MemberId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
