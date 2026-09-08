-- 미확정 결제의 복구 스케줄. 근거: docs/09-consistency-recovery.md §8, ADR-007
--
-- 충전(top_up_recovery)과 같은 모양입니다. 복구 상태는 업무 Aggregate가 아니라 운영 관심사이므로
-- 결제 행에 섞지 않고 별도 표에 둡니다. 정상적으로 끝난 결제는 여기에 흔적을 남기지 않습니다.
CREATE TABLE payment_recovery (
    payment_id             UUID        PRIMARY KEY REFERENCES payment (payment_id),
    attempt_count          INT         NOT NULL DEFAULT 0,
    -- 외부에 기록이 "없음"으로 확인된 연속 횟수입니다. 청구가 없었다는 근거가 쌓입니다.
    not_found_count        INT         NOT NULL DEFAULT 0,
    next_check_at          TIMESTAMPTZ NOT NULL,
    requires_manual_review BOOLEAN     NOT NULL DEFAULT FALSE,
    last_error             VARCHAR(500),
    last_checked_at        TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_payment_recovery_counts CHECK (attempt_count >= 0 AND not_found_count >= 0)
);

CREATE INDEX ix_payment_recovery_due ON payment_recovery (next_check_at)
    WHERE requires_manual_review = FALSE;
CREATE INDEX ix_payment_recovery_manual ON payment_recovery (requires_manual_review, updated_at)
    WHERE requires_manual_review = TRUE;
