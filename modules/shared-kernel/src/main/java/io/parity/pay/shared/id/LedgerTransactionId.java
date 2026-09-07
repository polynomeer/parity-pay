package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record LedgerTransactionId(UUID value) implements TypedId {

    public LedgerTransactionId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static LedgerTransactionId generate() {
        return new LedgerTransactionId(UuidV7.generate());
    }

    public static LedgerTransactionId of(UUID value) {
        return new LedgerTransactionId(value);
    }

    public static LedgerTransactionId of(String value) {
        return new LedgerTransactionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
