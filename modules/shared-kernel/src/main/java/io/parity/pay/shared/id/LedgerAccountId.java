package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record LedgerAccountId(UUID value) implements TypedId {

    public LedgerAccountId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static LedgerAccountId generate() {
        return new LedgerAccountId(UuidV7.generate());
    }

    public static LedgerAccountId of(UUID value) {
        return new LedgerAccountId(value);
    }

    public static LedgerAccountId of(String value) {
        return new LedgerAccountId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
