-- 판매자 계정. 근거: docs/02-prd.md §6(권한 모델), FR-016

-- 지금까지 merchant_id는 결제·정산 행에 적히기만 하는 UUID였고, 그 판매자가 누구인지 시스템은
-- 몰랐습니다. 판매자가 자기 정산을 조회하려면 "이 로그인 계정이 이 merchant_id의 주인"이라는
-- 사실이 어딘가에 있어야 합니다.
CREATE TABLE merchant (
    merchant_id     UUID         PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    owner_member_id UUID         NOT NULL REFERENCES member (member_id),
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    -- 한 계정은 판매자 하나만 대표합니다. 여러 판매자를 대표하는 구조가 필요해지면 별도
    -- 매핑 테이블로 확장합니다. 지금 없는 요구를 미리 만들지 않습니다.
    CONSTRAINT uq_merchant_owner UNIQUE (owner_member_id),
    CONSTRAINT ck_merchant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- 정산·결제의 merchant_id에는 아직 외래키를 걸지 않습니다. 기존 데이터에 등록되지 않은 판매자
-- 식별자가 들어 있고, 결제 요청도 판매자 등록 여부를 검증하지 않습니다. 검증을 추가하는 것은
-- 별도 작업이며, 그때 이 제약을 함께 겁니다.
