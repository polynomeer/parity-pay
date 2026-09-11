package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import io.parity.pay.api.member.MemberAccountRepository;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 변경과 재설정.
 *
 * <p>두 흐름이 공유하는 규칙이 있습니다.
 *
 * <ul>
 *   <li>비밀번호가 바뀌면 그 사용자의 리프레시 토큰을 **모두** 철회합니다. 비밀번호를 바꾸는 이유
 *       중 하나가 "누가 내 계정을 쓰고 있다"이고, 그때 남아 있는 세션이 계속 살아 있으면 바꾼
 *       의미가 없습니다.
 *   <li>살아 있는 재설정 토큰도 함께 죽입니다. 남겨 두면 나중에 그 토큰으로 다시 바꿀 수 있습니다.
 *   <li>계정 존재 여부를 응답으로 알려주지 않습니다.
 * </ul>
 *
 * <p>근거: docs/05-technical-design.md §11, NFR-007
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MemberAccountRepository memberAccountRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptTracker loginAttemptTracker;
    private final PasswordResetDelivery delivery;
    private final SecurityProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    PasswordService(
            MemberAccountRepository memberAccountRepository,
            TokenService tokenService,
            PasswordEncoder passwordEncoder,
            LoginAttemptTracker loginAttemptTracker,
            PasswordResetDelivery delivery,
            SecurityProperties properties,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.memberAccountRepository = memberAccountRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptTracker = loginAttemptTracker;
        this.delivery = delivery;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 로그인한 사용자가 현재 비밀번호를 알고 있을 때 바꿉니다.
     *
     * <p>현재 비밀번호 확인 실패는 로그인 실패와 같이 셉니다. 이 경로를 열어 두면 잠금을 우회해
     * 비밀번호를 추측할 수 있기 때문입니다.
     */
    @Transactional
    public void change(MemberId memberId, String currentPassword, String newPassword) {
        MemberAccount account = verifyAndLoad(memberId, currentPassword);
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "the new password must differ from the current one");
        }

        applyNewPassword(account, newPassword, "PASSWORD_CHANGED");
        loginAttemptTracker.clearFailures(account.email());
    }

    /**
     * 비밀번호가 맞는지만 확인합니다. 재인증(step-up)이 씁니다.
     *
     * <p>실패는 로그인 실패와 같이 셉니다. 비밀번호를 확인하는 경로가 하나라도 잠금 밖에 있으면
     * 그 경로로 추측할 수 있습니다. 성공하면 실패 기록을 지웁니다 — 로그인 성공과 같습니다.
     */
    @Transactional
    public void verify(MemberId memberId, String password) {
        MemberAccount account = verifyAndLoad(memberId, password);
        loginAttemptTracker.clearFailures(account.email());
    }

    private MemberAccount verifyAndLoad(MemberId memberId, String password) {
        MemberAccount account = memberAccountRepository
                .findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "member not found"));
        loginAttemptTracker.requireNotLocked(account.email());
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            loginAttemptTracker.recordFailure(account.email());
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "current password is not correct");
        }
        return account;
    }

    /**
     * 재설정을 요청합니다.
     *
     * <p>없는 계정이든 있는 계정이든 호출자에게는 같은 결과입니다. 다르게 응답하면 이 API가 가입
     * 여부 조회 API가 됩니다.
     */
    @Transactional
    public void requestReset(String email) {
        Optional<MemberAccount> account = memberAccountRepository.findByEmail(email);
        if (account.isEmpty() || !account.get().isActive()) {
            log.info("password reset requested for an unknown or inactive account");
            return;
        }

        MemberAccount member = account.get();
        // 살아 있는 토큰은 하나만 둡니다. 여러 개가 동시에 유효하면 그중 하나만 새면 됩니다.
        invalidateLiveTokens(member.memberId());

        String rawToken = newToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.passwordResetTtl());
        jdbcTemplate.update(
                """
                INSERT INTO password_reset_token (token_id, member_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                member.memberId().value(),
                TokenService.sha256(rawToken),
                Timestamp.from(now),
                Timestamp.from(expiresAt));

        delivery.deliver(member, rawToken, expiresAt);
    }

    /**
     * 재설정 토큰으로 비밀번호를 바꿉니다.
     *
     * <p>토큰이 없거나, 이미 썼거나, 만료됐거나, 무효화됐으면 모두 같은 응답입니다. 어느 쪽인지
     * 알려주면 토큰을 추측하는 사람에게 힌트가 됩니다.
     *
     * <p>성공하면 로그인 실패 기록도 지웁니다. 잠긴 계정을 푸는 정상 경로가 재설정입니다.
     */
    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT token_id, member_id, expires_at, used_at, invalidated_at
                  FROM password_reset_token
                 WHERE token_hash = ?
                """,
                TokenService.sha256(rawToken));
        if (rows.isEmpty()) {
            throw invalidToken();
        }

        Map<String, Object> row = rows.get(0);
        if (row.get("used_at") != null || row.get("invalidated_at") != null) {
            throw invalidToken();
        }
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        if (!expiresAt.isAfter(clock.instant())) {
            throw invalidToken();
        }

        MemberId memberId = MemberId.of((UUID) row.get("member_id"));
        MemberAccount account = memberAccountRepository
                .findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "member not found"));

        jdbcTemplate.update(
                "UPDATE password_reset_token SET used_at = ? WHERE token_id = ?",
                Timestamp.from(clock.instant()),
                row.get("token_id"));

        applyNewPassword(account, newPassword, "PASSWORD_RESET");
        loginAttemptTracker.clearFailures(account.email());
    }

    private void applyNewPassword(MemberAccount account, String newPassword, String reason) {
        memberAccountRepository.updatePasswordHash(account.memberId(), passwordEncoder.encode(newPassword));
        invalidateLiveTokens(account.memberId());
        int revoked = tokenService.revokeAllFor(account.memberId(), reason);
        log.info("{} for member {}; revoked {} refresh tokens", reason, account.memberId(), revoked);
    }

    private void invalidateLiveTokens(MemberId memberId) {
        jdbcTemplate.update(
                """
                UPDATE password_reset_token
                   SET invalidated_at = ?
                 WHERE member_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """,
                Timestamp.from(clock.instant()),
                memberId.value());
    }

    private static BusinessException invalidToken() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "the reset token is not valid");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
