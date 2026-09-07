package io.parity.pay.wallet.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TopUpJpaRepository extends JpaRepository<TopUpJpaEntity, UUID> {}
