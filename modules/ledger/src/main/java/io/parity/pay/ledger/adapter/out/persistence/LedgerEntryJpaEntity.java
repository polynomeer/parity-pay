package io.parity.pay.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** 전기된 원장 항목. Hibernate 수준에서도 변경을 막습니다. 근거: INV-006 */
@Entity
@Immutable
@Table(name = "ledger_entry")
// Hibernate가 리플렉션으로 읽고 쓰는 필드가 있습니다(감사 시각, @Version 낙관적 잠금).
// 코드에서 직접 읽지 않는다고 지우면 매핑이 깨집니다.
@SuppressWarnings("UnusedVariable")
class LedgerEntryJpaEntity {

    @Id
    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "direction", nullable = false, length = 6)
    private String direction;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryJpaEntity() {}

    LedgerEntryJpaEntity(
            UUID entryId, UUID transactionId, UUID accountId, String direction, long amount, Instant createdAt) {
        this.entryId = entryId;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    UUID entryId() {
        return entryId;
    }

    UUID transactionId() {
        return transactionId;
    }

    UUID accountId() {
        return accountId;
    }

    String direction() {
        return direction;
    }

    long amount() {
        return amount;
    }
}
