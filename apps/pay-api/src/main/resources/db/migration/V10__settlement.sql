-- 구매확정과 판매자 정산. 근거: docs/04-payment-policy.md §8, docs/06-domain-state-design.md §2

-- 구매확정. 주문 모듈이 없으므로 결제를 기준으로 기록합니다.
CREATE TABLE order_confirmation (
    payment_id   UUID         PRIMARY KEY REFERENCES payment (payment_id),
    order_id     VARCHAR(100) NOT NULL,
    confirmed_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_order_confirmation_order UNIQUE (order_id)
);

CREATE TABLE settlement (
    settlement_id         UUID         PRIMARY KEY,
    merchant_id           UUID         NOT NULL,
    period_start          DATE         NOT NULL,
    period_end            DATE         NOT NULL,
    gross_amount          BIGINT       NOT NULL,
    cancellation_amount   BIGINT       NOT NULL,
    fee_amount            BIGINT       NOT NULL,
    adjustment_amount     BIGINT       NOT NULL,
    net_amount            BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    external_reference_id VARCHAR(100),
    hold_reason           VARCHAR(200),
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    paid_at               TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_settlement_status
        CHECK (status IN ('CALCULATED', 'HELD', 'PAYING', 'PAID', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_settlement_amounts
        CHECK (gross_amount >= 0 AND cancellation_amount >= 0 AND fee_amount >= 0),
    -- 순액은 구성 항목의 계산 결과와 같아야 합니다. 근거: INV-008
    CONSTRAINT ck_settlement_net
        CHECK (net_amount = gross_amount - cancellation_amount - fee_amount + adjustment_amount),
    -- 음수 지급은 하지 않습니다. 순액이 음수면 다음 회차로 이월하거나 회수 절차를 따릅니다.
    CONSTRAINT ck_settlement_net_non_negative CHECK (net_amount >= 0),
    -- 지급 완료 정산은 반드시 외부 지급 참조를 가집니다. 근거: INV-009
    CONSTRAINT ck_settlement_paid_has_reference
        CHECK (status <> 'PAID' OR external_reference_id IS NOT NULL),
    CONSTRAINT uq_settlement_period UNIQUE (merchant_id, period_start, period_end)
);

CREATE INDEX ix_settlement_status ON settlement (status, updated_at);
CREATE INDEX ix_settlement_unknown ON settlement (status, updated_at) WHERE status = 'UNKNOWN';

CREATE TABLE settlement_item (
    item_id             UUID         PRIMARY KEY,
    settlement_id       UUID         REFERENCES settlement (settlement_id),
    merchant_id         UUID         NOT NULL,
    payment_id          UUID         NOT NULL,
    item_type           VARCHAR(20)  NOT NULL,
    -- 부호 있는 금액입니다. 원장이 아니므로 방향을 부호로 표현합니다.
    -- SALE(+), CANCELLATION(-), FEE(-), ADJUSTMENT(+/-)
    amount              BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    -- 이 항목을 만들어낸 업무 사실입니다. 중복 정산을 막는 키입니다. 근거: docs/04-payment-policy.md §8
    source_reference_id VARCHAR(100) NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_settlement_item_type
        CHECK (item_type IN ('SALE', 'CANCELLATION', 'FEE', 'ADJUSTMENT')),
    CONSTRAINT ck_settlement_item_status CHECK (status IN ('ELIGIBLE', 'SETTLED')),
    CONSTRAINT ck_settlement_item_amount CHECK (amount <> 0),
    -- 같은 업무 사실은 한 번만 정산에 들어갑니다.
    CONSTRAINT uq_settlement_item_source UNIQUE (item_type, source_reference_id)
);

CREATE INDEX ix_settlement_item_eligible ON settlement_item (merchant_id, occurred_at)
    WHERE status = 'ELIGIBLE';
CREATE INDEX ix_settlement_item_settlement ON settlement_item (settlement_id);
CREATE INDEX ix_settlement_item_payment ON settlement_item (payment_id);

-- 지급 결과가 불명확한 정산의 복구 스케줄입니다. 충전 복구와 같은 구조입니다.
CREATE TABLE settlement_recovery (
    settlement_id          UUID        PRIMARY KEY REFERENCES settlement (settlement_id),
    attempt_count          INT         NOT NULL DEFAULT 0,
    not_found_count        INT         NOT NULL DEFAULT 0,
    next_check_at          TIMESTAMPTZ NOT NULL,
    requires_manual_review BOOLEAN     NOT NULL DEFAULT FALSE,
    last_error             VARCHAR(500),
    last_checked_at        TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_settlement_recovery_due ON settlement_recovery (next_check_at)
    WHERE requires_manual_review = FALSE;

-- Mock Bank의 판매자 지급 기록입니다. 외부기관 쪽 멱등성을 재현합니다.
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
