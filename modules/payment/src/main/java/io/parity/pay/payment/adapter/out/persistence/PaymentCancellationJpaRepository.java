package io.parity.pay.payment.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentCancellationJpaRepository extends JpaRepository<PaymentCancellationJpaEntity, UUID> {}
