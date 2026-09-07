-- 충전. 근거: docs/08-db-api-event-spec.md §2, docs/06-domain-state-design.md §2

CREATE TABLE top_up (
    top_up_id             UUID         PRIMARY KEY,
    wallet_id             UUID         NOT NULL REFERENCES wallet (wallet_id),
    bank_account_id       UUID         NOT NULL REFERENCES bank_account (bank_account_id),
    requested_amount      BIGINT       NOT NULL,
    completed_amount      BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    idempotency_key       VARCHAR(100) NOT NULL,
    external_reference_id VARCHAR(100),
    failure_reason        VARCHAR(200),
    requested_at          TIMESTAMPTZ  NOT NULL,
    completed_at          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_top_up_requested_amount CHECK (requested_amount > 0),
    CONSTRAINT ck_top_up_completed_amount
        CHECK (completed_amount >= 0 AND completed_amount <= requested_amount),
    CONSTRAINT ck_top_up_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    -- 같은 지갑에서 같은 멱등 키로 두 건이 생기지 않습니다. 멱등 레코드가 사라져도 남는 방어선입니다.
    -- 근거: docs/04-payment-policy.md §5 (5)
    CONSTRAINT uq_top_up_wallet_idempotency UNIQUE (wallet_id, idempotency_key)
);

-- 미확정 복구 작업이 UNKNOWN 건을 찾는 인덱스입니다. 근거: docs/08-db-api-event-spec.md §3
CREATE INDEX ix_top_up_unknown ON top_up (status, requested_at) WHERE status = 'UNKNOWN';
CREATE INDEX ix_top_up_wallet ON top_up (wallet_id, requested_at DESC, top_up_id DESC);
