-- 이 기관의 장부입니다. **우리 데이터베이스가 아닙니다.**
--
-- 2026-09-09 이전에는 이 표들을 pay-api의 Flyway가 만들고 같은 데이터베이스에 두었습니다. 기관을
-- 별도 프로세스로 떼어낸 뒤에도 데이터는 우리 것과 섞여 있었고, 그래서 우리 쪽 코드가 마음만 먹으면
-- 기관의 표를 조인할 수 있었습니다. 실제 기관에서는 불가능한 일입니다.
--
-- 근거: docs/05-technical-design.md §10

CREATE TABLE mock_bank_account (
    mock_account_id      UUID         PRIMARY KEY,
    account_number_token VARCHAR(100) NOT NULL,
    balance              BIGINT       NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_mock_bank_account_token UNIQUE (account_number_token),
    CONSTRAINT ck_mock_bank_balance CHECK (balance >= 0)
);

-- 외부기관 쪽 멱등성입니다. 같은 external_key로 재요청해도 출금은 한 번만 일어납니다.
-- 근거: docs/09-consistency-recovery.md §7
CREATE TABLE mock_bank_withdrawal (
    withdrawal_id   UUID         PRIMARY KEY,
    external_key    VARCHAR(100) NOT NULL,
    mock_account_id UUID         NOT NULL REFERENCES mock_bank_account (mock_account_id),
    amount          BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_mock_bank_withdrawal_external_key UNIQUE (external_key),
    CONSTRAINT ck_mock_bank_withdrawal_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_bank_withdrawal_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);

-- 판매자 지급 기록입니다. 외부기관 쪽 멱등성을 재현합니다.
CREATE TABLE mock_bank_payout (
    payout_id    UUID         PRIMARY KEY,
    external_key VARCHAR(100) NOT NULL,
    merchant_id  UUID         NOT NULL,
    amount       BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_mock_bank_payout_external_key UNIQUE (external_key),
    CONSTRAINT ck_mock_bank_payout_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_bank_payout_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);

-- 대사 명세 조회용입니다. 기관도 자기 명세를 기간으로 뽑습니다.
CREATE INDEX ix_mock_bank_withdrawal_created ON mock_bank_withdrawal (created_at);
CREATE INDEX ix_mock_bank_payout_created ON mock_bank_payout (created_at);
