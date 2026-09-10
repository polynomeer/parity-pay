package io.parity.pay.api.security;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API. 근거: docs/03-mvp-scope.md §4
 *
 * <p>액세스 토큰은 짧게 살고, 리프레시 토큰은 교환할 때마다 회전합니다.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthenticationService authenticationService;
    private final PasswordService passwordService;
    private final CurrentPrincipal currentPrincipal;
    private final RefreshTokenCookie refreshTokenCookie;

    AuthController(
            AuthenticationService authenticationService,
            PasswordService passwordService,
            CurrentPrincipal currentPrincipal,
            RefreshTokenCookie refreshTokenCookie) {
        this.authenticationService = authenticationService;
        this.passwordService = passwordService;
        this.currentPrincipal = currentPrincipal;
        this.refreshTokenCookie = refreshTokenCookie;
    }

    @PostMapping("/tokens")
    ResponseEntity<TokenResponse> issue(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authenticationService.login(request.email(), request.password()));
    }

    /**
     * 리프레시 토큰을 회전시킵니다. 근거: ADR-010
     *
     * <p>토큰은 <b>본문이 아니라 쿠키</b>로 옵니다. 브라우저가 자동으로 붙이므로 클라이언트 코드는
     * 값을 만지지 않습니다. 만질 수 있게 만들면 `localStorage`로 돌아가는 것과 같습니다.
     */
    @Parameter(
            in = ParameterIn.COOKIE,
            name = RefreshTokenCookie.NAME,
            required = true,
            description = "리프레시 토큰. 브라우저가 자동으로 붙입니다 (ADR-010).")
    @PostMapping("/tokens/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        String refreshToken = refreshTokenCookie
                .read(request)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "refresh token cookie is missing"));
        return withRefreshCookie(authenticationService.refresh(refreshToken));
    }

    /** 이 사용자의 리프레시 토큰을 모두 철회합니다. 액세스 토큰은 만료로 사라집니다. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        authenticationService.logout(currentPrincipal.memberId());
        // 서버에서 철회해도 브라우저에는 죽은 쿠키가 남습니다. 함께 지웁니다.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear())
                .build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(TokenService.IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(tokens.refreshToken()))
                .body(TokenResponse.from(tokens));
    }

    /**
     * 비밀번호 변경. 현재 비밀번호를 알고 있는 사람만 바꿀 수 있습니다.
     *
     * <p>성공하면 이 사용자의 리프레시 토큰이 모두 철회됩니다. 다른 기기의 세션도 함께 끊깁니다.
     * 비밀번호를 바꾸는 이유가 "누가 내 계정을 쓰고 있다"일 수 있기 때문입니다.
     */
    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        passwordService.change(currentPrincipal.memberId(), request.currentPassword(), request.newPassword());
        // 세션이 전부 철회됐습니다. 이 브라우저의 쿠키도 함께 지웁니다.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear())
                .build();
    }

    /**
     * 비밀번호 재설정 요청.
     *
     * <p>가입한 이메일이든 아니든 같은 응답입니다. 다르게 답하면 이 API로 가입 여부를 조회할 수
     * 있게 됩니다. 토큰은 응답에 실리지 않고 전달 채널로만 나갑니다.
     */
    @PostMapping("/password-reset")
    ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    /** 재설정 토큰으로 새 비밀번호를 설정합니다. 토큰은 한 번만 쓸 수 있습니다. */
    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    record PasswordResetRequest(@Email @NotBlank String email) {}

    record PasswordResetConfirmRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    /**
     * 발급 결과입니다.
     *
     * <p><b>리프레시 토큰은 여기 없습니다.</b> `HttpOnly` 쿠키로 나가며, 본문에 다시 넣으면
     * ADR-010을 되돌리는 것입니다.
     */
    record TokenResponse(String accessToken, String tokenType, long expiresIn, UUID memberId, List<String> roles) {

        static TokenResponse from(TokenService.IssuedTokens tokens) {
            return new TokenResponse(
                    tokens.accessToken(),
                    "Bearer",
                    tokens.expiresInSeconds(),
                    tokens.memberId().value(),
                    tokens.roles().stream().map(Role::name).sorted().toList());
        }
    }
}
