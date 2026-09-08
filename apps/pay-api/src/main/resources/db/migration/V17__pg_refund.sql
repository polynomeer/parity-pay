-- 외부 PG 환불. 근거: docs/07-ledger-journal-catalog.md JE-014, ADR-007

-- Mock PG의 환불 기록입니다. 승인과 별도 표인 이유는 외부기관에서도 별개의 거래이기 때문입니다.
CREATE TABLE mock_pg_refund (
    refund_id    UUID         PRIMARY KEY,
    -- 우리가 보낸 업무 멱등 키(cancellationId)입니다.
    external_key VARCHAR(100) NOT NULL,
    payment_key  VARCHAR(100) NOT NULL,
    amount       BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_mock_pg_refund_external_key UNIQUE (external_key),
    CONSTRAINT ck_mock_pg_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_pg_refund_status CHECK (status IN ('REFUNDED', 'DECLINED'))
);

-- 미확정 취소의 복구 스케줄입니다. 결제·충전과 같은 모양입니다.
CREATE TABLE cancellation_recovery (
    cancellation_id        UUID        PRIMARY KEY REFERENCES payment_cancellation (cancellation_id),
    attempt_count          INT         NOT NULL DEFAULT 0,
    not_found_count        INT         NOT NULL DEFAULT 0,
    next_check_at          TIMESTAMPTZ NOT NULL,
    requires_manual_review BOOLEAN     NOT NULL DEFAULT FALSE,
    last_error             VARCHAR(500),
    last_checked_at        TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_cancellation_recovery_counts CHECK (attempt_count >= 0 AND not_found_count >= 0)
);

CREATE INDEX ix_cancellation_recovery_due ON cancellation_recovery (next_check_at)
    WHERE requires_manual_review = FALSE;
CREATE INDEX ix_cancellation_recovery_manual ON cancellation_recovery (requires_manual_review, updated_at)
    WHERE requires_manual_review = TRUE;
