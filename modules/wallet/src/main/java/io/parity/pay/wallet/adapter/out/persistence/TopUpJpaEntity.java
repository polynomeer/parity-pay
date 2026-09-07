package io.parity.pay.wallet.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "top_up")
// Hibernate가 리플렉션으로 읽고 쓰는 필드가 있습니다(감사 시각, @Version 낙관적 잠금).
// 코드에서 직접 읽지 않는다고 지우면 매핑이 깨집니다.
@SuppressWarnings("UnusedVariable")
class TopUpJpaEntity {

    @Id
    @Column(name = "top_up_id", nullable = false)
    private UUID topUpId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "requested_amount", nullable = false, updatable = false)
    private long requestedAmount;

    @Column(name = "completed_amount", nullable = false)
    private long completedAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "external_reference_id", length = 100)
    private String externalReferenceId;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected TopUpJpaEntity() {}

    TopUpJpaEntity(
            UUID topUpId,
            UUID walletId,
            UUID bankAccountId,
            long requestedAmount,
            long completedAmount,
            String currency,
            String status,
            String idempotencyKey,
            String externalReferenceId,
            String failureReason,
            Instant requestedAt,
            Instant completedAt) {
        this.topUpId = topUpId;
        this.walletId = walletId;
        this.bankAccountId = bankAccountId;
        this.requestedAmount = requestedAmount;
        this.completedAmount = completedAmount;
        this.currency = currency;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.externalReferenceId = externalReferenceId;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    void applyTransition(
            String status,
            long completedAmount,
            String externalReferenceId,
            String failureReason,
            Instant completedAt) {
        this.status = status;
        this.completedAmount = completedAmount;
        this.externalReferenceId = externalReferenceId;
        this.failureReason = failureReason;
        this.completedAt = completedAt;
    }

    UUID topUpId() {
        return topUpId;
    }

    UUID walletId() {
        return walletId;
    }

    UUID bankAccountId() {
        return bankAccountId;
    }

    long requestedAmount() {
        return requestedAmount;
    }

    long completedAmount() {
        return completedAmount;
    }

    String currency() {
        return currency;
    }

    String status() {
        return status;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    String externalReferenceId() {
        return externalReferenceId;
    }

    String failureReason() {
        return failureReason;
    }

    Instant requestedAt() {
        return requestedAt;
    }

    Instant completedAt() {
        return completedAt;
    }
}
