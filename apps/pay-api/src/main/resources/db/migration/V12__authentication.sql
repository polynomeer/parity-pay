-- 인증·인가. 근거: docs/02-prd.md §6(권한 모델), docs/05-technical-design.md §11

-- 역할은 쉼표로 구분해 저장합니다. 사용자당 역할이 몇 개 되지 않고 조회가 항상 사용자 단위이므로
-- 별도 테이블을 두지 않습니다.
ALTER TABLE member ADD COLUMN roles VARCHAR(200) NOT NULL DEFAULT 'CUSTOMER';

CREATE INDEX ix_member_roles ON member (roles);

-- 리프레시 토큰. 액세스 토큰과 수명·철회 정책을 분리하기 위해 서버가 상태를 가집니다.
-- 근거: docs/05-technical-design.md §11
CREATE TABLE refresh_token (
    token_id    UUID         PRIMARY KEY,
    member_id   UUID         NOT NULL REFERENCES member (member_id),
    -- 토큰 원문을 저장하지 않습니다. 유출 시 그대로 사용 가능한 값을 DB에 남기지 않기 위해서입니다.
    token_hash  CHAR(64)     NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    revoke_reason VARCHAR(60),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_token_member ON refresh_token (member_id, expires_at DESC);

-- 로그인 실패 추적. 같은 계정에 대한 반복 시도를 잠급니다.
-- 근거: docs/05-technical-design.md §11(로그인 실패 제한)
CREATE TABLE login_attempt (
    email             VARCHAR(255) PRIMARY KEY,
    failed_count      INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    last_failed_at    TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL
);
