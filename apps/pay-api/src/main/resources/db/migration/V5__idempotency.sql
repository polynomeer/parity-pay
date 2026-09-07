-- 멱등 요청 기록. 근거: docs/04-payment-policy.md §5, docs/09-consistency-recovery.md §3

CREATE TABLE idempotency_record (
    principal_id          UUID         NOT NULL,
    operation             VARCHAR(60)  NOT NULL,
    idempotency_key       VARCHAR(100) NOT NULL,
    -- 요청 본문 원문을 저장하지 않고 정규화 해시만 남깁니다.
    request_hash          CHAR(64)     NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    business_reference_id UUID,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (principal_id, operation, idempotency_key),
    CONSTRAINT ck_idempotency_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'RECOVERY_REQUIRED'))
);

-- 오래 PROCESSING으로 남은 요청을 찾는 인덱스입니다(StaleIdempotencyResolver).
CREATE INDEX ix_idempotency_stale ON idempotency_record (status, updated_at)
    WHERE status IN ('PROCESSING', 'RECOVERY_REQUIRED');
