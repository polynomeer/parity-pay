-- Mock Bank. 실제 은행이 아니라 장애 시나리오를 재현하기 위한 대역입니다.
-- 근거: docs/05-technical-design.md §10, docs/03-mvp-scope.md §2

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
