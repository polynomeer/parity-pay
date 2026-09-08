-- 비밀번호 재설정. 근거: docs/05-technical-design.md §11, NFR-007

-- 토큰 원문을 저장하지 않습니다. 리프레시 토큰과 같은 이유입니다. DB가 유출되면 그대로 계정을
-- 가져갈 수 있는 값을 남기지 않습니다.
CREATE TABLE password_reset_token (
    token_id       UUID        PRIMARY KEY,
    member_id      UUID        NOT NULL REFERENCES member (member_id),
    token_hash     CHAR(64)    NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    -- 한 번 쓰면 끝입니다. 사용 시각을 남겨 재사용을 막고 사후 조사에 쓸 수 있게 합니다.
    used_at        TIMESTAMPTZ,
    -- 새 토큰이 발급되거나 비밀번호가 다른 경로로 바뀌면 남은 토큰을 죽입니다.
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash)
);

-- 살아 있는 토큰만 찾습니다.
CREATE INDEX ix_password_reset_live ON password_reset_token (member_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;
