-- 회원, 지갑, 잔액 스냅샷, 연결 계좌.
-- 근거: docs/08-db-api-event-spec.md §2

CREATE TABLE member (
    member_id     UUID         PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_member_email UNIQUE (email),
    CONSTRAINT ck_member_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE wallet (
    wallet_id  UUID        PRIMARY KEY,
    member_id  UUID        NOT NULL REFERENCES member (member_id),
    currency   VARCHAR(3)  NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- 사용자·통화별 지갑은 하나입니다. 근거: docs/04-payment-policy.md §2
    CONSTRAINT uq_wallet_member_currency UNIQUE (member_id, currency),
    CONSTRAINT ck_wallet_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

-- 잔액 스냅샷입니다. 진실의 원천은 원장이며 이 표는 조회용 파생 데이터입니다.
-- 근거: ADR-008, INV-010
CREATE TABLE wallet_balance (
    wallet_id        UUID        PRIMARY KEY REFERENCES wallet (wallet_id),
    available_amount BIGINT      NOT NULL,
    pending_amount   BIGINT      NOT NULL,
    version          BIGINT      NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    -- INV-003: 가용 잔액은 음수가 될 수 없습니다. 애플리케이션 검증에만 의존하지 않습니다.
    CONSTRAINT ck_wallet_balance_available_non_negative CHECK (available_amount >= 0),
    CONSTRAINT ck_wallet_balance_pending_non_negative CHECK (pending_amount >= 0)
);

CREATE TABLE bank_account (
    bank_account_id       UUID         PRIMARY KEY,
    member_id             UUID         NOT NULL REFERENCES member (member_id),
    bank_code             VARCHAR(10)  NOT NULL,
    -- 계좌번호 원문을 저장하지 않습니다. 근거: FR-002, docs/05-technical-design.md §11
    account_number_token  VARCHAR(100) NOT NULL,
    account_number_masked VARCHAR(30)  NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_bank_account_member_token UNIQUE (member_id, account_number_token),
    CONSTRAINT ck_bank_account_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_wallet_member ON wallet (member_id);
CREATE INDEX ix_bank_account_member ON bank_account (member_id);
