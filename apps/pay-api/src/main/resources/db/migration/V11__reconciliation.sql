-- 대사 실행과 불일치. 근거: docs/09-consistency-recovery.md §10, docs/08-db-api-event-spec.md §2

CREATE TABLE reconciliation_run (
    run_id         UUID        PRIMARY KEY,
    run_type       VARCHAR(30) NOT NULL,
    window_start   TIMESTAMPTZ NOT NULL,
    window_end     TIMESTAMPTZ NOT NULL,
    internal_count INT         NOT NULL,
    external_count INT         NOT NULL,
    mismatch_count INT         NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reconciliation_run_type CHECK (run_type IN ('TOP_UP', 'SETTLEMENT_PAYOUT'))
);

CREATE INDEX ix_reconciliation_run_type ON reconciliation_run (run_type, started_at DESC);

CREATE TABLE reconciliation_mismatch (
    mismatch_id                     UUID         PRIMARY KEY,
    run_id                          UUID         NOT NULL REFERENCES reconciliation_run (run_id),
    mismatch_type                   VARCHAR(20)  NOT NULL,
    reference_type                  VARCHAR(30)  NOT NULL,
    reference_id                    VARCHAR(100) NOT NULL,
    external_reference_id           VARCHAR(100),
    internal_amount                 BIGINT,
    external_amount                 BIGINT,
    currency                        VARCHAR(3),
    detail                          VARCHAR(300),
    resolution_status               VARCHAR(20)  NOT NULL,
    resolution_type                 VARCHAR(40),
    resolved_by                     VARCHAR(100),
    resolution_reason               VARCHAR(300),
    adjustment_ledger_transaction_id UUID,
    detected_at                     TIMESTAMPTZ  NOT NULL,
    resolved_at                     TIMESTAMPTZ,
    CONSTRAINT ck_reconciliation_mismatch_type
        CHECK (mismatch_type IN ('INTERNAL_ONLY', 'EXTERNAL_ONLY', 'STATUS_MISMATCH',
                                 'AMOUNT_MISMATCH', 'DUPLICATE', 'LEDGER_MISSING')),
    CONSTRAINT ck_reconciliation_resolution_status
        CHECK (resolution_status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    -- 해결된 건은 누가 왜 해결했는지가 반드시 남습니다.
    CONSTRAINT ck_reconciliation_resolution_evidence
        CHECK (resolution_status = 'OPEN'
               OR (resolved_by IS NOT NULL AND resolution_reason IS NOT NULL))
);

-- 같은 차이가 대사를 돌 때마다 새로 쌓이면 운영자가 노이즈에 묻힙니다.
-- 미해결 상태의 같은 차이는 한 건만 유지합니다.
CREATE UNIQUE INDEX uq_reconciliation_open_mismatch
    ON reconciliation_mismatch (reference_type, reference_id, mismatch_type)
    WHERE resolution_status = 'OPEN';

CREATE INDEX ix_reconciliation_mismatch_open
    ON reconciliation_mismatch (resolution_status, mismatch_type, detected_at DESC);
