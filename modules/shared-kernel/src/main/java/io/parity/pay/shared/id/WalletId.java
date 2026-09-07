package io.parity.pay.shared.id;

import java.util.Objects;
import java.util.UUID;

/** 타입 안전한 식별자. 근거: docs/06-domain-state-design.md §3 */
public record WalletId(UUID value) implements TypedId {

    public WalletId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static WalletId generate() {
        return new WalletId(UuidV7.generate());
    }

    public static WalletId of(UUID value) {
        return new WalletId(value);
    }

    public static WalletId of(String value) {
        return new WalletId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
