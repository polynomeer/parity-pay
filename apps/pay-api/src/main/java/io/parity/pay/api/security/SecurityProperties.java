package io.parity.pay.api.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정.
 *
 * <p>{@code bootstrapOperators}는 운영자 계정을 처음 만들기 위한 장치입니다. 운영자가 하나도 없으면
 * 아무도 운영 API를 쓸 수 없고, 운영자를 만들 권한도 운영자에게 있기 때문입니다. 운영 환경에서는
 * 비워 두고 별도 절차로 계정을 만듭니다.
 */
@ConfigurationProperties(prefix = "paritypay.security")
public record SecurityProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int maxLoginFailures,
        Duration loginLockDuration,
        List<BootstrapOperator> bootstrapOperators) {

    public SecurityProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(14) : refreshTokenTtl;
        maxLoginFailures = maxLoginFailures <= 0 ? 5 : maxLoginFailures;
        loginLockDuration = loginLockDuration == null ? Duration.ofMinutes(10) : loginLockDuration;
        bootstrapOperators = bootstrapOperators == null ? List.of() : List.copyOf(bootstrapOperators);
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "paritypay.security.jwt-secret must be at least 32 characters");
        }
    }

    public record BootstrapOperator(String email, String password, List<Role> roles) {}
}
