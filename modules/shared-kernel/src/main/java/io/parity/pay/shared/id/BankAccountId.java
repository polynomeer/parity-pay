package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record BankAccountId(UUID value) implements TypedId {

    public BankAccountId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static BankAccountId generate() {
        return new BankAccountId(UuidV7.generate());
    }

    public static BankAccountId of(UUID value) {
        return new BankAccountId(value);
    }

    public static BankAccountId of(String value) {
        return new BankAccountId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
