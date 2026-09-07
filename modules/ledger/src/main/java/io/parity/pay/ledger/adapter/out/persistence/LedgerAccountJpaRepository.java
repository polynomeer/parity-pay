package io.parity.pay.ledger.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerAccountJpaRepository extends JpaRepository<LedgerAccountJpaEntity, UUID> {

    @Query(
            """
            select a from LedgerAccountJpaEntity a
            where a.accountCode = :accountCode
              and a.currency = :currency
              and ((:ownerId is null and a.ownerId is null) or a.ownerId = :ownerId)
            """)
    Optional<LedgerAccountJpaEntity> findByCodeAndOwner(
            @Param("accountCode") String accountCode,
            @Param("ownerId") UUID ownerId,
            @Param("currency") String currency);
}
