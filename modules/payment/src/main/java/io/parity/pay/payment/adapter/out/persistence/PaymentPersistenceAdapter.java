package io.parity.pay.payment.adapter.out.persistence;

import io.parity.pay.payment.application.port.out.PaymentCancellationRepository;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.domain.CancellationStatus;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 결제·취소 out 포트의 JPA 구현. */
@Repository
class PaymentPersistenceAdapter implements PaymentRepository, PaymentCancellationRepository {

    private final PaymentJpaRepository paymentJpaRepository;
    private final PaymentCancellationJpaRepository cancellationJpaRepository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    PaymentPersistenceAdapter(
            PaymentJpaRepository paymentJpaRepository,
            PaymentCancellationJpaRepository cancellationJpaRepository,
            Clock clock) {
        this.paymentJpaRepository = paymentJpaRepository;
        this.cancellationJpaRepository = cancellationJpaRepository;
        this.clock = clock;
    }

    @Override
    public Optional<Payment> findById(PaymentId paymentId) {
        return paymentJpaRepository.findById(paymentId.value()).map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Payment> findActiveByOrderId(String orderId) {
        return paymentJpaRepository.findActiveByOrderId(orderId).map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity existing =
                entityManager.find(PaymentJpaEntity.class, payment.id().value());
        if (existing == null) {
            entityManager.persist(new PaymentJpaEntity(
                    payment.id().value(),
                    payment.orderId(),
                    payment.memberId().value(),
                    payment.walletId().value(),
                    payment.merchantId().value(),
                    payment.requestedAmount().amount(),
                    payment.approvedAmount().amount(),
                    payment.completedCancellationAmount().amount(),
                    payment.processingCancellationAmount().amount(),
                    payment.requestedAmount().currency().name(),
                    payment.method().name(),
                    payment.status().name(),
                    payment.idempotencyKey().value(),
                    payment.externalReferenceId(),
                    payment.failureReason(),
                    payment.createdAt(),
                    payment.approvedAt(),
                    payment.updatedAt()));
        } else {
            // 취소 금액은 조건부 UPDATE만 변경합니다. 여기서는 상태·승인액만 반영합니다.
            existing.applyExternalStatus(
                    payment.status().name(),
                    payment.approvedAmount().amount(),
                    payment.externalReferenceId(),
                    payment.failureReason(),
                    payment.approvedAt(),
                    payment.updatedAt());
        }
        return payment;
    }

    @Override
    public int reserveCancellation(PaymentId paymentId, Money amount) {
        return paymentJpaRepository.reserveCancellation(paymentId.value(), amount.amount(), clock.instant());
    }

    @Override
    public int completeCancellation(PaymentId paymentId, Money amount) {
        return paymentJpaRepository.completeCancellation(paymentId.value(), amount.amount(), clock.instant());
    }

    @Override
    public int releaseCancellation(PaymentId paymentId, Money amount) {
        return paymentJpaRepository.releaseCancellation(paymentId.value(), amount.amount(), clock.instant());
    }

    @Override
    public Optional<PaymentCancellation> findById(CancellationId cancellationId) {
        return cancellationJpaRepository.findById(cancellationId.value()).map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public PaymentCancellation save(PaymentCancellation cancellation) {
        PaymentCancellationJpaEntity existing = entityManager.find(
                PaymentCancellationJpaEntity.class, cancellation.id().value());
        if (existing == null) {
            entityManager.persist(new PaymentCancellationJpaEntity(
                    cancellation.id().value(),
                    cancellation.paymentId().value(),
                    cancellation.requestedAmount().amount(),
                    cancellation.completedAmount().amount(),
                    cancellation.requestedAmount().currency().name(),
                    cancellation.reason(),
                    cancellation.status().name(),
                    cancellation.idempotencyKey().value(),
                    cancellation.externalReferenceId(),
                    cancellation.requestedAt(),
                    cancellation.completedAt()));
        } else {
            existing.applyTransition(
                    cancellation.status().name(),
                    cancellation.completedAmount().amount(),
                    cancellation.externalReferenceId(),
                    cancellation.completedAt());
        }
        return cancellation;
    }

    private static Payment toDomain(PaymentJpaEntity entity) {
        CurrencyCode currency = CurrencyCode.valueOf(entity.currency());
        return new Payment(
                PaymentId.of(entity.paymentId()),
                entity.orderId(),
                MemberId.of(entity.memberId()),
                WalletId.of(entity.walletId()),
                MerchantId.of(entity.merchantId()),
                Money.of(entity.requestedAmount(), currency),
                Money.of(entity.approvedAmount(), currency),
                Money.of(entity.completedCancellationAmount(), currency),
                Money.of(entity.processingCancellationAmount(), currency),
                PaymentMethod.valueOf(entity.method()),
                PaymentStatus.valueOf(entity.status()),
                IdempotencyKey.of(entity.idempotencyKey()),
                entity.externalReferenceId(),
                entity.failureReason(),
                entity.createdAt(),
                entity.approvedAt(),
                entity.updatedAt());
    }

    private static PaymentCancellation toDomain(PaymentCancellationJpaEntity entity) {
        CurrencyCode currency = CurrencyCode.valueOf(entity.currency());
        return new PaymentCancellation(
                CancellationId.of(entity.cancellationId()),
                PaymentId.of(entity.paymentId()),
                Money.of(entity.requestedAmount(), currency),
                Money.of(entity.completedAmount(), currency),
                entity.reason(),
                CancellationStatus.valueOf(entity.status()),
                IdempotencyKey.of(entity.idempotencyKey()),
                entity.externalReferenceId(),
                entity.requestedAt(),
                entity.completedAt());
    }
}
