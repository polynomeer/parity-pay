package io.parity.pay.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_cancellation")
// Hibernate가 리플렉션으로 읽고 쓰는 필드가 있습니다(감사 시각, @Version 낙관적 잠금).
// 코드에서 직접 읽지 않는다고 지우면 매핑이 깨집니다.
@SuppressWarnings("UnusedVariable")
class PaymentCancellationJpaEntity {

    @Id
    @Column(name = "cancellation_id", nullable = false)
    private UUID cancellationId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "requested_amount", nullable = false, updatable = false)
    private long requestedAmount;

    @Column(name = "completed_amount", nullable = false)
    private long completedAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "external_reference_id", length = 100)
    private String externalReferenceId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentCancellationJpaEntity() {}

    PaymentCancellationJpaEntity(
            UUID cancellationId,
            UUID paymentId,
            long requestedAmount,
            long completedAmount,
            String currency,
            String reason,
            String status,
            String idempotencyKey,
            String externalReferenceId,
            Instant requestedAt,
            Instant completedAt) {
        this.cancellationId = cancellationId;
        this.paymentId = paymentId;
        this.requestedAmount = requestedAmount;
        this.completedAmount = completedAmount;
        this.currency = currency;
        this.reason = reason;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.externalReferenceId = externalReferenceId;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.version = 0L;
    }

    void applyTransition(String status, long completedAmount, String externalReferenceId, Instant completedAt) {
        this.status = status;
        this.completedAmount = completedAmount;
        this.externalReferenceId = externalReferenceId;
        this.completedAt = completedAt;
    }

    UUID cancellationId() {
        return cancellationId;
    }

    UUID paymentId() {
        return paymentId;
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

    String reason() {
        return reason;
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

    Instant requestedAt() {
        return requestedAt;
    }

    Instant completedAt() {
        return completedAt;
    }
}
