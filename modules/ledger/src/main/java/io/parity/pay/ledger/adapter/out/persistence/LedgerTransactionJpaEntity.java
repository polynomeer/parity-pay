package io.parity.pay.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * POSTED 이후에는 갱신하지 않습니다. DB 트리거도 UPDATE·DELETE를 거부합니다.
 * 근거: INV-006, V2__ledger_immutability.sql
 */
@Entity
@Table(name = "ledger_transaction")
class LedgerTransactionJpaEntity {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "transaction_type", nullable = false, length = 40)
    private String transactionType;

    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reversal_of_transaction_id")
    private UUID reversalOfTransactionId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransactionJpaEntity() {}

    LedgerTransactionJpaEntity(
            UUID transactionId,
            String transactionType,
            String referenceType,
            UUID referenceId,
            String currency,
            String status,
            UUID reversalOfTransactionId,
            Instant effectiveAt,
            Instant createdAt) {
        this.transactionId = transactionId;
        this.transactionType = transactionType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.currency = currency;
        this.status = status;
        this.reversalOfTransactionId = reversalOfTransactionId;
        this.effectiveAt = effectiveAt;
        this.createdAt = createdAt;
    }

    UUID transactionId() {
        return transactionId;
    }

    String transactionType() {
        return transactionType;
    }

    String referenceType() {
        return referenceType;
    }

    UUID referenceId() {
        return referenceId;
    }

    String currency() {
        return currency;
    }

    String status() {
        return status;
    }

    UUID reversalOfTransactionId() {
        return reversalOfTransactionId;
    }

    Instant effectiveAt() {
        return effectiveAt;
    }

    Instant createdAt() {
        return createdAt;
    }
}
