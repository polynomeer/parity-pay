package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import io.parity.pay.api.member.MemberAccountRepository;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인.
 *
 * <p>실패 사유를 구분해 알려주지 않습니다. "없는 계정"과 "틀린 비밀번호"를 구분해 응답하면 계정
 * 존재 여부를 알려주는 것이 됩니다. 반복 실패는 계정을 잠급니다.
 * 근거: docs/05-technical-design.md §11, NFR-007
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final MemberAccountRepository memberAccountRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptTracker loginAttemptTracker;

    AuthenticationService(
            MemberAccountRepository memberAccountRepository,
            TokenService tokenService,
            PasswordEncoder passwordEncoder,
            LoginAttemptTracker loginAttemptTracker) {
        this.memberAccountRepository = memberAccountRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptTracker = loginAttemptTracker;
    }

    @Transactional
    public TokenService.IssuedTokens login(String email, String rawPassword) {
        loginAttemptTracker.requireNotLocked(email);

        MemberAccount account = memberAccountRepository.findByEmail(email).orElse(null);
        if (account == null || !passwordEncoder.matches(rawPassword, account.passwordHash())) {
            loginAttemptTracker.recordFailure(email);
            // 계정 존재 여부를 노출하지 않기 위해 같은 응답을 돌려줍니다.
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "email or password is not correct");
        }
        if (!account.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "account is not active");
        }

        loginAttemptTracker.clearFailures(email);
        return tokenService.issue(account);
    }

    @Transactional
    public TokenService.IssuedTokens refresh(String refreshToken) {
        return tokenService.refresh(refreshToken, this::requireMember);
    }

    @Transactional
    public void logout(MemberId memberId) {
        int revoked = tokenService.revokeAllFor(memberId, "LOGOUT");
        log.info("revoked {} refresh tokens for member {}", revoked, memberId);
    }

    private MemberAccount requireMember(MemberId memberId) {
        return memberAccountRepository
                .findById(memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "member not found"));
    }

}
