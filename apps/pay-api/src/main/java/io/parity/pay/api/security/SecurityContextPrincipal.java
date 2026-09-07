package io.parity.pay.api.security;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.security.CurrentPrincipal;
import org.springframework.stereotype.Component;

/** {@link CurrentPrincipal}의 Spring Security 구현. */
@Component
class SecurityContextPrincipal implements CurrentPrincipal {

    @Override
    public MemberId memberId() {
        return AuthenticatedMember.current().memberId();
    }

    @Override
    public boolean hasRole(String role) {
        return AuthenticatedMember.current().hasRole(Role.valueOf(role));
    }

    @Override
    public String actorId() {
        AuthenticatedMember member = AuthenticatedMember.current();
        return member.email() == null ? member.memberId().toString() : member.email();
    }
}
