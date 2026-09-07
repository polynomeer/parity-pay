package io.parity.pay.wallet.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet")
class WalletJpaEntity {

    @Id
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletJpaEntity() {}

    WalletJpaEntity(UUID walletId, UUID memberId, String currency, String status, Instant createdAt) {
        this.walletId = walletId;
        this.memberId = memberId;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    UUID walletId() {
        return walletId;
    }

    UUID memberId() {
        return memberId;
    }

    String currency() {
        return currency;
    }

    String status() {
        return status;
    }

    Instant createdAt() {
        return createdAt;
    }

    void changeStatus(String status) {
        this.status = status;
    }
}
