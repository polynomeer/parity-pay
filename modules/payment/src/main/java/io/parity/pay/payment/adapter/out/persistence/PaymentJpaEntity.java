package io.parity.pay.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment")
// Hibernate가 리플렉션으로 읽고 쓰는 필드가 있습니다(감사 시각, @Version 낙관적 잠금).
// 코드에서 직접 읽지 않는다고 지우면 매핑이 깨집니다.
@SuppressWarnings("UnusedVariable")
class PaymentJpaEntity {

    @Id
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false, length = 100, updatable = false)
    private String orderId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "requested_amount", nullable = false, updatable = false)
    private long requestedAmount;

    @Column(name = "approved_amount", nullable = false)
    private long approvedAmount;

    @Column(name = "completed_cancellation_amount", nullable = false)
    private long completedCancellationAmount;

    @Column(name = "processing_cancellation_amount", nullable = false)
    private long processingCancellationAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "method", nullable = false, length = 30, updatable = false)
    private String method;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentJpaEntity() {}

    PaymentJpaEntity(
            UUID paymentId,
            String orderId,
            UUID memberId,
            UUID walletId,
            UUID merchantId,
            long requestedAmount,
            long approvedAmount,
            long completedCancellationAmount,
            long processingCancellationAmount,
            String currency,
            String method,
            String status,
            String idempotencyKey,
            Instant createdAt,
            Instant approvedAt,
            Instant updatedAt) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.memberId = memberId;
        this.walletId = walletId;
        this.merchantId = merchantId;
        this.requestedAmount = requestedAmount;
        this.approvedAmount = approvedAmount;
        this.completedCancellationAmount = completedCancellationAmount;
        this.processingCancellationAmount = processingCancellationAmount;
        this.currency = currency;
        this.method = method;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.approvedAt = approvedAt;
        this.updatedAt = updatedAt;
        this.version = 0L;
    }

    void applyStatus(String status, long approvedAmount, Instant approvedAt, Instant updatedAt) {
        this.status = status;
        this.approvedAmount = approvedAmount;
        this.approvedAt = approvedAt;
        this.updatedAt = updatedAt;
    }

    UUID paymentId() {
        return paymentId;
    }

    String orderId() {
        return orderId;
    }

    UUID memberId() {
        return memberId;
    }

    UUID walletId() {
        return walletId;
    }

    UUID merchantId() {
        return merchantId;
    }

    long requestedAmount() {
        return requestedAmount;
    }

    long approvedAmount() {
        return approvedAmount;
    }

    long completedCancellationAmount() {
        return completedCancellationAmount;
    }

    long processingCancellationAmount() {
        return processingCancellationAmount;
    }

    String currency() {
        return currency;
    }

    String method() {
        return method;
    }

    String status() {
        return status;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant approvedAt() {
        return approvedAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
