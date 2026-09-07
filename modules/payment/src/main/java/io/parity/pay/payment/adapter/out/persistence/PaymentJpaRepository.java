package io.parity.pay.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    @Query(
            """
            select p from PaymentJpaEntity p
             where p.orderId = :orderId
               and p.status in ('APPROVED', 'PARTIALLY_CANCELED')
            """)
    Optional<PaymentJpaEntity> findActiveByOrderId(@Param("orderId") String orderId);

    /**
     * 취소 가능액이 남아 있을 때만 예약합니다.
     *
     * <p>WHERE 절에 금액 조건과 상태 조건을 모두 넣은 단일 UPDATE입니다. 동시에 들어온 취소 요청은
     * 행 잠금 뒤 조건을 다시 평가하므로 누적 취소액이 승인액을 넘지 않습니다. 근거: INV-005
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update PaymentJpaEntity p
               set p.processingCancellationAmount = p.processingCancellationAmount + :amount,
                   p.version = p.version + 1,
                   p.updatedAt = :now
             where p.paymentId = :paymentId
               and p.status in ('APPROVED', 'PARTIALLY_CANCELED')
               and p.completedCancellationAmount + p.processingCancellationAmount + :amount
                   <= p.approvedAmount
            """)
    int reserveCancellation(
            @Param("paymentId") UUID paymentId,
            @Param("amount") long amount,
            @Param("now") Instant now);

    /** 예약을 확정 취소액으로 옮기고, 전액 취소가 되면 상태를 CANCELED로 바꿉니다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update PaymentJpaEntity p
               set p.processingCancellationAmount = p.processingCancellationAmount - :amount,
                   p.completedCancellationAmount = p.completedCancellationAmount + :amount,
                   p.status = case
                       when p.completedCancellationAmount + :amount = p.approvedAmount then 'CANCELED'
                       else 'PARTIALLY_CANCELED'
                   end,
                   p.version = p.version + 1,
                   p.updatedAt = :now
             where p.paymentId = :paymentId
               and p.processingCancellationAmount >= :amount
            """)
    int completeCancellation(
            @Param("paymentId") UUID paymentId,
            @Param("amount") long amount,
            @Param("now") Instant now);

    /** 취소가 실패했을 때 예약만 되돌립니다. 결제 상태는 바꾸지 않습니다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update PaymentJpaEntity p
               set p.processingCancellationAmount = p.processingCancellationAmount - :amount,
                   p.version = p.version + 1,
                   p.updatedAt = :now
             where p.paymentId = :paymentId
               and p.processingCancellationAmount >= :amount
            """)
    int releaseCancellation(
            @Param("paymentId") UUID paymentId,
            @Param("amount") long amount,
            @Param("now") Instant now);
}
