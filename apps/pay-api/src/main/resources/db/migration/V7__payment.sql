-- 결제와 취소. 근거: docs/08-db-api-event-spec.md §2

CREATE TABLE payment (
    payment_id                     UUID         PRIMARY KEY,
    order_id                       VARCHAR(100) NOT NULL,
    member_id                      UUID         NOT NULL REFERENCES member (member_id),
    wallet_id                      UUID         NOT NULL REFERENCES wallet (wallet_id),
    merchant_id                    UUID         NOT NULL,
    requested_amount               BIGINT       NOT NULL,
    approved_amount                BIGINT       NOT NULL,
    completed_cancellation_amount  BIGINT       NOT NULL,
    processing_cancellation_amount BIGINT       NOT NULL,
    currency                       VARCHAR(3)   NOT NULL,
    method                         VARCHAR(30)  NOT NULL,
    status                         VARCHAR(30)  NOT NULL,
    idempotency_key                VARCHAR(100) NOT NULL,
    created_at                     TIMESTAMPTZ  NOT NULL,
    approved_at                    TIMESTAMPTZ,
    updated_at                     TIMESTAMPTZ  NOT NULL,
    version                        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_requested_amount CHECK (requested_amount > 0),
    CONSTRAINT ck_payment_approved_amount
        CHECK (approved_amount >= 0 AND approved_amount <= requested_amount),
    CONSTRAINT ck_payment_completed_cancellation CHECK (completed_cancellation_amount >= 0),
    CONSTRAINT ck_payment_processing_cancellation CHECK (processing_cancellation_amount >= 0),
    -- INV-005: 누적 취소액은 승인액을 넘을 수 없습니다. 애플리케이션 검증만 믿지 않습니다.
    CONSTRAINT ck_payment_cancellation_within_approved
        CHECK (completed_cancellation_amount + processing_cancellation_amount <= approved_amount),
    CONSTRAINT ck_payment_status
        CHECK (status IN ('READY', 'PROCESSING', 'APPROVED', 'PARTIALLY_CANCELED',
                          'CANCELED', 'FAILED', 'UNKNOWN')),
    -- 같은 사용자가 같은 멱등 키로 두 건을 만들 수 없습니다. 근거: docs/08-db-api-event-spec.md §2
    CONSTRAINT uq_payment_member_idempotency UNIQUE (member_id, idempotency_key)
);

-- 주문당 성공 결제는 하나입니다. 실패한 시도는 재요청할 수 있으므로 부분 인덱스를 사용합니다.
-- 근거: docs/04-payment-policy.md §4, docs/06-domain-state-design.md §6
CREATE UNIQUE INDEX uq_payment_active_order ON payment (order_id)
    WHERE status IN ('APPROVED', 'PARTIALLY_CANCELED');

CREATE INDEX ix_payment_wallet ON payment (wallet_id, created_at DESC, payment_id DESC);
CREATE INDEX ix_payment_unknown ON payment (status, updated_at) WHERE status = 'UNKNOWN';

CREATE TABLE payment_cancellation (
    cancellation_id       UUID         PRIMARY KEY,
    payment_id            UUID         NOT NULL REFERENCES payment (payment_id),
    requested_amount      BIGINT       NOT NULL,
    completed_amount      BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    reason                VARCHAR(100),
    status                VARCHAR(20)  NOT NULL,
    idempotency_key       VARCHAR(100) NOT NULL,
    external_reference_id VARCHAR(100),
    requested_at          TIMESTAMPTZ  NOT NULL,
    completed_at          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_cancellation_requested_amount CHECK (requested_amount > 0),
    CONSTRAINT ck_cancellation_completed_amount
        CHECK (completed_amount >= 0 AND completed_amount <= requested_amount),
    CONSTRAINT ck_cancellation_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT uq_cancellation_payment_idempotency UNIQUE (payment_id, idempotency_key)
);

CREATE INDEX ix_cancellation_payment ON payment_cancellation (payment_id, requested_at);
CREATE INDEX ix_cancellation_unknown ON payment_cancellation (status, requested_at)
    WHERE status = 'UNKNOWN';
