package io.parity.pay.api.security;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.security.CurrentPrincipal;
import io.parity.pay.shared.security.RecentAuthentication;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * 재인증(step-up).
 *
 * <p>비밀번호를 다시 확인하면 짧게 사는 증거 토큰을 줍니다. 액세스 토큰과는 <b>다른 목적</b>의
 * 토큰입니다 — {@code purpose=reauth} 클레임이 있어야 하고, 액세스 토큰을 증거 자리에 넣으면
 * 거절됩니다. 그렇지 않으면 세션이 있다는 것과 방금 확인했다는 것이 같은 말이 되어 이 장치가
 * 없는 것과 같습니다.
 *
 * <p>리프레시 토큰이나 세션 상태를 건드리지 않습니다. 증거는 서명된 값이라 서버가 기억할 것이
 * 없고, 만료(5분)가 곧 유효 기간입니다.
 *
 * <p>비밀번호 확인 실패는 로그인 실패와 같이 셉니다. 이 경로를 열어 두면 잠금을 우회해 비밀번호를
 * 추측할 수 있기 때문입니다 — 비밀번호 변경과 같은 판단입니다.
 */
@Component
public class StepUpAuthentication implements RecentAuthentication {

    static final String PURPOSE = "reauth";

    private final PasswordService passwordService;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final CurrentPrincipal currentPrincipal;
    private final SecurityProperties properties;
    private final Clock clock;

    StepUpAuthentication(
            PasswordService passwordService,
            JwtEncoder jwtEncoder,
            JwtDecoder jwtDecoder,
            CurrentPrincipal currentPrincipal,
            SecurityProperties properties,
            Clock clock) {
        this.passwordService = passwordService;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.currentPrincipal = currentPrincipal;
        this.properties = properties;
        this.clock = clock;
    }

    /** 비밀번호를 확인하고 증거를 발급합니다. 틀리면 예외이며 실패 횟수에 셉니다. */
    public Proof issue(MemberId memberId, String password) {
        passwordService.verify(memberId, password);
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("parity-pay")
                .subject(memberId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.reauthTtl()))
                .claim("purpose", PURPOSE)
                .build();
        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Proof(token, properties.reauthTtl().toSeconds());
    }

    @Override
    public void require(String proof) {
        if (proof == null || proof.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "recent authentication is required");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(proof);
        } catch (JwtException e) {
            // 만료·위조·형식 오류를 구분해 알려 주지 않습니다. 어느 쪽이든 다시 확인하면 됩니다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "recent authentication is not valid");
        }
        // 액세스 토큰도 같은 키로 서명되어 여기까지 통과합니다. 목적 클레임이 가릅니다.
        if (!PURPOSE.equals(jwt.getClaimAsString("purpose"))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "recent authentication is not valid");
        }
        // 다른 사람의 증거로 내 작업을 승인할 수 없습니다.
        if (!currentPrincipal.memberId().toString().equals(jwt.getSubject())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "recent authentication is not valid");
        }
    }

    public record Proof(String token, long expiresInSeconds) {}
}
