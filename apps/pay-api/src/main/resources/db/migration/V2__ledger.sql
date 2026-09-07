-- 이중부기 원장. 근거: docs/07-ledger-journal-catalog.md §4, ADR-003

CREATE TABLE ledger_account (
    account_id   UUID        PRIMARY KEY,
    account_code VARCHAR(10) NOT NULL,
    owner_type   VARCHAR(20) NOT NULL,
    owner_id     UUID,
    currency     VARCHAR(3)  NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    -- 법인 계정은 owner_id가 NULL입니다. NULL도 같은 값으로 취급해 중복 생성을 막습니다.
    CONSTRAINT uq_ledger_account_owner
        UNIQUE NULLS NOT DISTINCT (account_code, currency, owner_id),
    CONSTRAINT ck_ledger_account_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE ledger_transaction (
    transaction_id             UUID        PRIMARY KEY,
    transaction_type           VARCHAR(40) NOT NULL,
    reference_type             VARCHAR(30) NOT NULL,
    reference_id               UUID        NOT NULL,
    currency                   VARCHAR(3)  NOT NULL,
    status                     VARCHAR(20) NOT NULL,
    reversal_of_transaction_id UUID        REFERENCES ledger_transaction (transaction_id),
    effective_at               TIMESTAMPTZ NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    -- INV-004: 같은 업무 참조의 금융 효과는 한 번만 반영됩니다. 멱등성의 최종 방어선입니다.
    CONSTRAINT uq_ledger_transaction_reference
        UNIQUE (reference_type, reference_id, transaction_type),
    CONSTRAINT ck_ledger_transaction_status CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED'))
);

CREATE TABLE ledger_entry (
    entry_id       UUID        PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transaction (transaction_id),
    account_id     UUID        NOT NULL REFERENCES ledger_account (account_id),
    direction      VARCHAR(6)  NOT NULL,
    amount         BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    -- INV-002: 금액은 항상 양수이고 증감은 direction으로 표현합니다.
    CONSTRAINT ck_ledger_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX ix_ledger_entry_account ON ledger_entry (account_id, entry_id);
CREATE INDEX ix_ledger_entry_transaction ON ledger_entry (transaction_id);
CREATE INDEX ix_ledger_transaction_reference ON ledger_transaction (reference_type, reference_id);
