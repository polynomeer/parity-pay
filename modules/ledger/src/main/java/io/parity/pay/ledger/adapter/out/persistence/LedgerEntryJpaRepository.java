package io.parity.pay.ledger.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryJpaEntity, UUID> {

    List<LedgerEntryJpaEntity> findByTransactionIdOrderByEntryId(UUID transactionId);

    /** 계정별 차변·대변 합계. 잔액은 계정의 정상 잔액 방향에 따라 애플리케이션에서 계산합니다. */
    @Query(
            """
            select
                coalesce(sum(case when e.direction = 'DEBIT' then e.amount else 0 end), 0) as debitTotal,
                coalesce(sum(case when e.direction = 'CREDIT' then e.amount else 0 end), 0) as creditTotal
            from LedgerEntryJpaEntity e
            where e.accountId = :accountId
            """)
    EntryTotalsProjection sumTotalsByAccountId(@Param("accountId") UUID accountId);

    interface EntryTotalsProjection {
        long getDebitTotal();

        long getCreditTotal();
    }
}
