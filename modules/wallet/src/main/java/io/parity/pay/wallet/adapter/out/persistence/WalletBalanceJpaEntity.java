package io.parity.pay.wallet.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 잔액 스냅샷 행. 증감은 조건부 단일 UPDATE로만 수행합니다. 근거: ADR-004 */
@Entity
@Table(name = "wallet_balance")
class WalletBalanceJpaEntity {

    @Id
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "available_amount", nullable = false)
    private long availableAmount;

    @Column(name = "pending_amount", nullable = false)
    private long pendingAmount;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletBalanceJpaEntity() {}

    WalletBalanceJpaEntity(UUID walletId, long availableAmount, long pendingAmount, long version, Instant updatedAt) {
        this.walletId = walletId;
        this.availableAmount = availableAmount;
        this.pendingAmount = pendingAmount;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    UUID walletId() {
        return walletId;
    }

    long availableAmount() {
        return availableAmount;
    }

    long pendingAmount() {
        return pendingAmount;
    }

    long version() {
        return version;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
