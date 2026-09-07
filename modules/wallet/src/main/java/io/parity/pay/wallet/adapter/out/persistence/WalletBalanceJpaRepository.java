package io.parity.pay.wallet.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WalletBalanceJpaRepository extends JpaRepository<WalletBalanceJpaEntity, UUID> {

    /** 충전·환불 등 증가. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update WalletBalanceJpaEntity b
               set b.availableAmount = b.availableAmount + :amount,
                   b.version = b.version + 1,
                   b.updatedAt = :now
             where b.walletId = :walletId
            """)
    int increaseAvailable(@Param("walletId") UUID walletId, @Param("amount") long amount, @Param("now") Instant now);

    /**
     * 스냅샷 재구축 전용 대입 갱신입니다.
     *
     * <p>버전이 그대로일 때만 씁니다. 원장을 읽은 뒤 여기까지 오는 사이에 정상 업무가 잔액을
     * 바꿨다면 그 결과를 덮어쓰지 않고 0행을 돌려줍니다. 근거: INV-010, ADR-008
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update WalletBalanceJpaEntity b
               set b.availableAmount = :available,
                   b.version = b.version + 1,
                   b.updatedAt = :now
             where b.walletId = :walletId
               and b.version = :expectedVersion
            """)
    int restoreAvailable(
            @Param("walletId") UUID walletId,
            @Param("available") long available,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    /**
     * 잔액이 충분할 때만 차감하는 조건부 원자 갱신입니다.
     *
     * <p>조회 후 계산 후 저장이 아니라 단일 UPDATE이므로 동시 요청 두 건이 모두 승인되는 경쟁 조건이
     * 발생하지 않습니다. 갱신 0행은 잔액 부족입니다. 근거: ADR-004, INV-003
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update WalletBalanceJpaEntity b
               set b.availableAmount = b.availableAmount - :amount,
                   b.version = b.version + 1,
                   b.updatedAt = :now
             where b.walletId = :walletId
               and b.availableAmount >= :amount
            """)
    int decreaseAvailableIfSufficient(
            @Param("walletId") UUID walletId, @Param("amount") long amount, @Param("now") Instant now);
}
