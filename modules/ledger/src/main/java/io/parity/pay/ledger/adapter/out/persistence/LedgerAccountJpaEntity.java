package io.parity.pay.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_account")
class LedgerAccountJpaEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_code", nullable = false, length = 10)
    private String accountCode;

    @Column(name = "owner_type", nullable = false, length = 20)
    private String ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccountJpaEntity() {}

    LedgerAccountJpaEntity(
            UUID accountId,
            String accountCode,
            String ownerType,
            UUID ownerId,
            String currency,
            String status,
            Instant createdAt) {
        this.accountId = accountId;
        this.accountCode = accountCode;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    UUID accountId() {
        return accountId;
    }

    String accountCode() {
        return accountCode;
    }

    UUID ownerId() {
        return ownerId;
    }

    String currency() {
        return currency;
    }

    String status() {
        return status;
    }
}
