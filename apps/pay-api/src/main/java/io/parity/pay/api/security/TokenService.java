package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 발급과 철회.
 *
 * <p>액세스 토큰은 짧게 살고 서명만으로 검증합니다. 리프레시 토큰은 길게 살지만 서버가 상태를
 * 가지고 있어 즉시 철회할 수 있습니다. 두 토큰의 수명과 철회 정책을 분리한 이유입니다.
 * 근거: docs/05-technical-design.md §11
 *
 * <p>리프레시 토큰 원문은 저장하지 않고 해시만 남깁니다. DB가 유출되어도 그대로 쓸 수 있는 값이
 * 남지 않게 하기 위해서입니다.
 */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final SecurityProperties properties;
    private final Clock clock;

    TokenService(JwtEncoder jwtEncoder, JdbcTemplate jdbcTemplate, SecurityProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedTokens issue(MemberAccount account) {
        Instant now = clock.instant();
        String accessToken = encodeAccessToken(account, now);
        String refreshToken = newRefreshToken();

        jdbcTemplate.update(
                """
                INSERT INTO refresh_token (token_id, member_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                account.memberId().value(),
                sha256(refreshToken),
                Timestamp.from(now),
                Timestamp.from(now.plus(properties.refreshTokenTtl())));

        return new IssuedTokens(
                accessToken,
                refreshToken,
                properties.accessTokenTtl().toSeconds(),
                account.memberId(),
                account.roles());
    }

    /**
     * 리프레시 토큰을 새 토큰 쌍으로 교환합니다.
     *
     * <p>사용한 토큰은 즉시 철회합니다(회전). 같은 리프레시 토큰이 두 번 쓰이면 유출을 의심할 수
     * 있어야 하기 때문입니다.
     */
    @Transactional
    public IssuedTokens refresh(String refreshToken, MemberLookup lookup) {
        String hash = sha256(refreshToken);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT token_id, member_id, expires_at, revoked_at
                  FROM refresh_token
                 WHERE token_hash = ?
                """,
                hash);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "refresh token is not valid");
        }

        Map<String, Object> row = rows.get(0);
        if (row.get("revoked_at") != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "refresh token was revoked");
        }
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        if (!expiresAt.isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "refresh token has expired");
        }

        // 위의 SELECT는 두 요청이 동시에 오면 둘 다 "철회 안 됨"을 봅니다. 회전은 여기서 원자적으로
        // 결정됩니다 — 아직 철회되지 않은 행을 철회한 쪽만 새 토큰을 받습니다. 그래야 "같은 토큰이
        // 두 번 쓰이면 거절된다"가 동시 요청에서도 참입니다.
        if (!revokeIfActive((UUID) row.get("token_id"), "ROTATED")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "refresh token was revoked");
        }
        MemberAccount account = lookup.byId(MemberId.of((UUID) row.get("member_id")));
        return issue(account);
    }

    /** 사용자의 모든 리프레시 토큰을 철회합니다. 비밀번호 변경·유출 대응에 사용합니다. */
    @Transactional
    public int revokeAllFor(MemberId memberId, String reason) {
        return jdbcTemplate.update(
                """
                UPDATE refresh_token
                   SET revoked_at = ?, revoke_reason = ?
                 WHERE member_id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(clock.instant()),
                reason,
                memberId.value());
    }

    /** 아직 살아 있는 토큰만 철회합니다. 이미 철회됐으면 {@code false}입니다. */
    private boolean revokeIfActive(UUID tokenId, String reason) {
        return jdbcTemplate.update(
                        "UPDATE refresh_token SET revoked_at = ?, revoke_reason = ?"
                                + " WHERE token_id = ? AND revoked_at IS NULL",
                        Timestamp.from(clock.instant()),
                        reason,
                        tokenId)
                == 1;
    }

    private String encodeAccessToken(MemberAccount account, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("parity-pay")
                .subject(account.memberId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("email", account.email())
                .claim("roles", account.roles().stream().map(Role::name).collect(Collectors.toList()))
                .build();
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    /** 순환 의존을 피하기 위해 회원 조회를 호출자가 넘깁니다. */
    public interface MemberLookup {
        MemberAccount byId(MemberId memberId);
    }

    public record IssuedTokens(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            MemberId memberId,
            java.util.Set<Role> roles) {}
}
