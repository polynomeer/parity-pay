package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record SettlementId(UUID value) implements TypedId {

    public SettlementId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static SettlementId generate() {
        return new SettlementId(UuidV7.generate());
    }

    public static SettlementId of(UUID value) {
        return new SettlementId(value);
    }

    public static SettlementId of(String value) {
        return new SettlementId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
