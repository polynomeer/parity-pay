-- Transactional Outbox, 소비 이력, 거래내역 프로젝션.
-- 근거: ADR-005, ADR-006, docs/09-consistency-recovery.md §5·§6

-- 업무 데이터와 같은 트랜잭션에 기록됩니다. 커밋되면 발행 의도가 남고, 롤백되면 함께 사라집니다.
CREATE TABLE outbox_event (
    event_id        UUID         PRIMARY KEY,
    event_type      VARCHAR(60)  NOT NULL,
    event_version   INT          NOT NULL,
    aggregate_type  VARCHAR(30)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    partition_key   VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    trace_id        VARCHAR(64),
    status          VARCHAR(20)  NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_event_version CHECK (event_version >= 1)
);

-- 발행기 폴링용 인덱스입니다. 근거: docs/08-db-api-event-spec.md §3
CREATE INDEX ix_outbox_pending ON outbox_event (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_outbox_status ON outbox_event (status, occurred_at);

-- ADR-006: 전달은 at-least-once입니다. 소비자는 (consumer_name, event_id)로 중복 효과를 막습니다.
CREATE TABLE consumed_event (
    consumer_name VARCHAR(60) NOT NULL,
    event_id      UUID        NOT NULL,
    consumed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_consumed_event PRIMARY KEY (consumer_name, event_id)
);

-- 거래내역 프로젝션. 이벤트로부터 재구축 가능한 파생 데이터이며 진실의 원천이 아닙니다.
-- 지갑·결제 테이블에 FK를 두지 않습니다. 프로젝션이 업무 테이블의 생명주기를 붙잡으면 안 되고,
-- 재구축을 위해 통째로 지우고 다시 만들 수 있어야 하기 때문입니다.
CREATE TABLE wallet_transaction (
    transaction_id        UUID         PRIMARY KEY,
    wallet_id             UUID         NOT NULL,
    transaction_type      VARCHAR(30)  NOT NULL,
    direction             VARCHAR(6)   NOT NULL,
    amount                BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    reference_type        VARCHAR(30)  NOT NULL,
    reference_id          VARCHAR(100) NOT NULL,
    ledger_transaction_id UUID,
    occurred_at           TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_wallet_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_wallet_transaction_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    -- 업무 유니크 키입니다. 같은 이벤트가 다시 전달되어도 내역은 한 줄만 생깁니다.
    -- consumed_event와 함께 쓰는 두 번째 방어선입니다. 근거: ADR-006, INV-004
    CONSTRAINT uq_wallet_transaction_reference UNIQUE (reference_type, reference_id)
);

-- 커서 페이지네이션용 정렬 인덱스입니다. 근거: docs/08-db-api-event-spec.md §9
CREATE INDEX ix_wallet_transaction_cursor
    ON wallet_transaction (wallet_id, occurred_at DESC, transaction_id DESC);
