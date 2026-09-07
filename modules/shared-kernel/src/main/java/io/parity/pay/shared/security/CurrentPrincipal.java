package io.parity.pay.shared.security;

import io.parity.pay.shared.id.MemberId;

/**
 * 지금 요청을 보낸 주체.
 *
 * <p>업무 모듈의 어댑터는 인증이 어떻게 구현되었는지 알 필요가 없습니다. "누가 호출했는가"만
 * 알면 되고, 그것을 검증하는 방법은 조립 지점이 결정합니다.
 *
 * <p>이 포트가 생기기 전에는 컨트롤러가 {@code X-Member-Id} 헤더를 그대로 믿었습니다.
 * 근거: docs/02-prd.md §6, docs/05-technical-design.md §6
 */
public interface CurrentPrincipal {

    /** 인증된 사용자 ID입니다. 인증되지 않았으면 예외입니다. */
    MemberId memberId();

    /** 운영 권한 확인이 필요한 곳에서 사용합니다. */
    boolean hasRole(String role);

    /** 감사 로그에 남길 주체 식별자입니다. */
    String actorId();
}
