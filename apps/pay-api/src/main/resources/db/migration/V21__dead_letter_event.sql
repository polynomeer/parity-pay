-- 소비자가 처리하지 못한 레코드의 기록. 근거: reports/11 M-018 결함 M, ADR-006, docs/09 §6
--
-- 소비자가 재시도를 다 쓰고도 처리하지 못한 레코드는 조용히 건너뛰지 않습니다. 여기에 남기고 DLT
-- 토픽으로도 보낸 뒤에야 오프셋을 넘어갑니다. 쓰레기 레코드라면 여기 한 줄로 끝이지만, 우리 이벤트가
-- 역직렬화 버그로 실패한 것이라면 이 표가 "거래내역·정산에서 빠진 이벤트"의 유일한 목록입니다.
--
-- 같은 레코드(그룹·토픽·파티션·오프셋)는 한 줄입니다. 복구 중 DLT 발행이 실패해 다시 시도되면
-- 같은 자리에 다시 쓰므로 유니크가 필요합니다.
CREATE TABLE dead_letter_event (
    dead_letter_id   UUID         NOT NULL,
    consumer_group   VARCHAR(60)  NOT NULL,
    topic            VARCHAR(100) NOT NULL,
    partition_no     INTEGER      NOT NULL,
    record_offset    BIGINT       NOT NULL,
    record_key       VARCHAR(200),
    payload          TEXT         NOT NULL,
    -- 봉투가 파싱됐으면 채웁니다. 파싱조차 안 되는 레코드는 비어 있습니다.
    event_id         UUID,
    event_type       VARCHAR(60),
    error            TEXT         NOT NULL,
    -- OPEN: 운영자 확인 대기 · RETRIED: 원 토픽으로 다시 흘려보냄
    status           VARCHAR(20)  NOT NULL,
    failed_at        TIMESTAMPTZ  NOT NULL,
    retried_at       TIMESTAMPTZ,
    retried_by       VARCHAR(100),
    CONSTRAINT pk_dead_letter_event PRIMARY KEY (dead_letter_id),
    CONSTRAINT uq_dead_letter_event_record UNIQUE (consumer_group, topic, partition_no, record_offset),
    CONSTRAINT ck_dead_letter_event_status CHECK (status IN ('OPEN', 'RETRIED'))
);

CREATE INDEX ix_dead_letter_event_status_failed_at ON dead_letter_event (status, failed_at);
