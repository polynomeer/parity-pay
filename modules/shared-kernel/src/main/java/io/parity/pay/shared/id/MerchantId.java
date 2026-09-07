package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record MerchantId(UUID value) implements TypedId {

    public MerchantId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static MerchantId generate() {
        return new MerchantId(UuidV7.generate());
    }

    public static MerchantId of(UUID value) {
        return new MerchantId(value);
    }

    public static MerchantId of(String value) {
        return new MerchantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
