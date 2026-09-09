-- 이 기관의 장부입니다. **우리 데이터베이스가 아닙니다.** 근거: docs/05-technical-design.md §10

CREATE TABLE mock_pg_approval (
    approval_id  UUID         PRIMARY KEY,
    -- 우리가 보낸 업무 멱등 키(paymentId)입니다. 같은 키의 재요청이 중복 승인이 되지 않습니다.
    external_key VARCHAR(100) NOT NULL,
    merchant_id  UUID         NOT NULL,
    order_id     VARCHAR(100) NOT NULL,
    amount       BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_mock_pg_approval_external_key UNIQUE (external_key),
    CONSTRAINT ck_mock_pg_approval_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_pg_approval_status CHECK (status IN ('APPROVED', 'DECLINED'))
);

-- 환불은 승인과 별도 표입니다. 외부기관에서도 별개의 거래이기 때문입니다.
CREATE TABLE mock_pg_refund (
    refund_id    UUID         PRIMARY KEY,
    -- 우리가 보낸 업무 멱등 키(cancellationId)입니다.
    external_key VARCHAR(100) NOT NULL,
    payment_key  VARCHAR(100) NOT NULL,
    amount       BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_mock_pg_refund_external_key UNIQUE (external_key),
    CONSTRAINT ck_mock_pg_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_mock_pg_refund_status CHECK (status IN ('REFUNDED', 'DECLINED'))
);
