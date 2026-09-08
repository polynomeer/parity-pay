package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 전달 수단이 없는 기본 구현.
 *
 * <p>토큰을 로그에 쓰지 않습니다. 그렇게 하면 로그 열람 권한이 계정 탈취 권한이 됩니다. 요청이
 * 있었다는 사실과 회원 ID만 남기고, 전달 채널이 설정되지 않았다고 경고합니다.
 */
@Component
@Profile("!local & !test")
class UnconfiguredPasswordResetDelivery implements PasswordResetDelivery {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredPasswordResetDelivery.class);

    @Override
    public void deliver(MemberAccount account, String rawToken, Instant expiresAt) {
        log.warn(
                "password reset requested for member {} but no delivery channel is configured;"
                        + " the token was discarded",
                account.memberId());
    }
}
