package io.parity.pay.ledger.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerTransactionJpaRepository extends JpaRepository<LedgerTransactionJpaEntity, UUID> {

    Optional<LedgerTransactionJpaEntity> findByReferenceTypeAndReferenceIdAndTransactionType(
            String referenceType, UUID referenceId, String transactionType);
}
