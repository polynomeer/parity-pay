-- 외부 PG 결제. 근거: docs/04-payment-policy.md §4, docs/07-ledger-journal-catalog.md JE-013

-- 외부 참조와 거절 사유를 결제 행에 남깁니다. 충전(top_up)과 같은 이유입니다. 이 값이 없으면
-- 결과를 모를 때 무엇을 조회해야 하는지, 왜 거절됐는지 나중에 알 수 없습니다.
ALTER TABLE payment ADD COLUMN external_reference_id VARCHAR(100);
ALTER TABLE payment ADD COLUMN failure_reason        VARCHAR(200);

-- 같은 외부 승인을 두 결제가 참조하면 이중 청구를 우리 쪽에서 못 알아봅니다.
CREATE UNIQUE INDEX uq_payment_external_reference
    ON payment (external_reference_id) WHERE external_reference_id IS NOT NULL;

-- 미확정 결제를 찾는 복구 조회용입니다.
CREATE INDEX ix_payment_unresolved ON payment (status, updated_at)
    WHERE status IN ('PROCESSING', 'UNKNOWN');

-- Mock PG. 외부기관 대역이며 우리 원장이 아닙니다. 대사할 때 "외부는 무엇을 알고 있는가"의
-- 자리를 차지합니다. 근거: docs/05-technical-design.md §10
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
