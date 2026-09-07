-- 외부 결과 복구 상태와 운영 감사 로그.
-- 근거: docs/09-consistency-recovery.md §7·§8, NFR-006

-- 복구 스케줄은 업무 Aggregate가 아니라 운영 관심사이므로 별도 표에 둡니다.
-- 정상 흐름에서는 이 표에 아무것도 쓰지 않습니다.
CREATE TABLE top_up_recovery (
    top_up_id             UUID         PRIMARY KEY REFERENCES top_up (top_up_id),
    attempt_count         INT          NOT NULL DEFAULT 0,
    -- 외부에 기록이 "없음"으로 확인된 연속 횟수입니다. 자금이 나가지 않았다는 근거가 쌓입니다.
    not_found_count       INT          NOT NULL DEFAULT 0,
    next_check_at         TIMESTAMPTZ  NOT NULL,
    -- 자동 재시도를 중단하고 운영자·대사 대상으로 넘겼는지 여부입니다.
    requires_manual_review BOOLEAN     NOT NULL DEFAULT FALSE,
    last_error            VARCHAR(500),
    last_checked_at       TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_top_up_recovery_counts
        CHECK (attempt_count >= 0 AND not_found_count >= 0)
);

CREATE INDEX ix_top_up_recovery_due ON top_up_recovery (next_check_at)
    WHERE requires_manual_review = FALSE;
CREATE INDEX ix_top_up_recovery_manual ON top_up_recovery (requires_manual_review, updated_at)
    WHERE requires_manual_review = TRUE;

-- 운영자 작업 감사 로그. append-only이며 주체·사유·전후·결과를 남깁니다. 근거: NFR-006
CREATE TABLE audit_log (
    audit_id     UUID         PRIMARY KEY,
    actor        VARCHAR(100) NOT NULL,
    action       VARCHAR(60)  NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id  VARCHAR(100) NOT NULL,
    reason       VARCHAR(300),
    before_state VARCHAR(100),
    after_state  VARCHAR(100),
    result       VARCHAR(20)  NOT NULL,
    detail       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCEEDED', 'FAILED', 'NO_CHANGE'))
);

CREATE INDEX ix_audit_resource ON audit_log (resource_type, resource_id, created_at DESC);

-- 감사 로그는 지우거나 고칠 수 없습니다. 운영자가 자신의 흔적을 지울 수 있으면 감사가 아닙니다.
CREATE FUNCTION audit_log_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'NFR-006: audit_log is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_is_append_only();
