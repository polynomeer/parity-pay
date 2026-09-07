package io.parity.pay.api.security;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 인증된 호출자.
 *
 * <p>이전에는 {@code X-Member-Id} 헤더를 그대로 믿었습니다. 이제 사용자 식별은 서명된 토큰에서만
 * 옵니다. 소유권 검사는 그대로 유지합니다. 인증은 "누구인지"를, 소유권 검사는 "이 자원이 그의
 * 것인지"를 답하며 둘 다 필요합니다. 근거: docs/02-prd.md §6, NFR-007
 */
public record AuthenticatedMember(MemberId memberId, String email, Set<Role> roles) {

    public static AuthenticatedMember current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "authentication is required");
        }
        return from(token);
    }

    public static AuthenticatedMember from(JwtAuthenticationToken token) {
        Jwt jwt = token.getToken();
        Set<Role> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> Role.valueOf(authority.substring("ROLE_".length())))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AuthenticatedMember(MemberId.of(jwt.getSubject()), jwt.getClaimAsString("email"), roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
