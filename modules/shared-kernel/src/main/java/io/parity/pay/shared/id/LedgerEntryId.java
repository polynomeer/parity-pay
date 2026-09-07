package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record LedgerEntryId(UUID value) implements TypedId {

    public LedgerEntryId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static LedgerEntryId generate() {
        return new LedgerEntryId(UuidV7.generate());
    }

    public static LedgerEntryId of(UUID value) {
        return new LedgerEntryId(value);
    }

    public static LedgerEntryId of(String value) {
        return new LedgerEntryId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
