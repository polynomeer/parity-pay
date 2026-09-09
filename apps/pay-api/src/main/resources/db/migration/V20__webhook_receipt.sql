-- 웹훅 수신 이력. 근거: F-008, docs/09-consistency-recovery.md §11, ADR-006
--
-- 기관은 웹훅을 at-least-once로 보냅니다. 응답을 못 받으면 다시 보내고, 재시도가 원래 것보다 먼저
-- 도착하기도 합니다. 그래서 두 가지를 막아야 합니다.
--
-- 1. 중복: 같은 기관 이벤트가 두 번 와도 효과는 한 번입니다. (provider, event_id)로 막습니다.
-- 2. 역순: 오래된 웹훅이 나중에 도착해 최신 상태를 되돌리면 안 됩니다. 기관이 붙이는 순번을
--    업무 키별로 기억하고, 그보다 작거나 같은 것은 무시합니다.
--
-- 여기서 막지 못해도 금액이 두 번 움직이지는 않습니다. 웹훅은 payload를 믿지 않고 상태 조회를
-- 촉발할 뿐이며, 확정 경로는 복구 작업과 같은 하나입니다.
CREATE TABLE webhook_receipt (
    provider     VARCHAR(30)  NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    external_key VARCHAR(100) NOT NULL,
    event_type   VARCHAR(40)  NOT NULL,
    sequence_no  BIGINT       NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_webhook_receipt PRIMARY KEY (provider, event_id),
    CONSTRAINT ck_webhook_receipt_sequence CHECK (sequence_no >= 0)
);

-- 업무 키별로 마지막에 반영한 순번입니다. 역순 웹훅을 가려내는 기준이고, 수신 이력과 따로 두는
-- 이유는 이력이 "받았다"의 기록인 반면 이것은 "어디까지 반영했다"의 기록이기 때문입니다.
CREATE TABLE webhook_cursor (
    provider     VARCHAR(30)  NOT NULL,
    external_key VARCHAR(100) NOT NULL,
    sequence_no  BIGINT       NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_webhook_cursor PRIMARY KEY (provider, external_key)
);

CREATE INDEX ix_webhook_receipt_key ON webhook_receipt (provider, external_key, received_at DESC);
