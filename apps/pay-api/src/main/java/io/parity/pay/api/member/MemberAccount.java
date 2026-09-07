package io.parity.pay.api.member;

import io.parity.pay.api.security.Role;
import io.parity.pay.shared.id.MemberId;
import java.util.Set;

/**
 * 인증에 필요한 최소한의 회원 정보.
 *
 * <p>회원 관리는 아직 별도 모듈이 아니라 조립 지점에 있습니다. 인증·가입·지갑 생성이 서로 얽혀
 * 있어 모듈로 떼어내는 이득이 크지 않았습니다. 근거: docs/05-technical-design.md §4 (초기 MVP)
 */
public record MemberAccount(
        MemberId memberId, String email, String passwordHash, String status, Set<Role> roles) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
