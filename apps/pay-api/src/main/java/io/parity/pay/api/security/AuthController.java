package io.parity.pay.api.security;

import io.parity.pay.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
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

    AuthController(
            AuthenticationService authenticationService,
            PasswordService passwordService,
            CurrentPrincipal currentPrincipal) {
        this.authenticationService = authenticationService;
        this.passwordService = passwordService;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping("/tokens")
    ResponseEntity<TokenResponse> issue(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(TokenResponse.from(authenticationService.login(request.email(), request.password())));
    }

    @PostMapping("/tokens/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(TokenResponse.from(authenticationService.refresh(request.refreshToken())));
    }

    /** 이 사용자의 리프레시 토큰을 모두 철회합니다. 액세스 토큰은 만료로 사라집니다. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        authenticationService.logout(currentPrincipal.memberId());
        return ResponseEntity.noContent().build();
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
        return ResponseEntity.noContent().build();
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

    record RefreshRequest(@NotBlank String refreshToken) {}

    record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            UUID memberId,
            List<String> roles) {

        static TokenResponse from(TokenService.IssuedTokens tokens) {
            return new TokenResponse(
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    "Bearer",
                    tokens.expiresInSeconds(),
                    tokens.memberId().value(),
                    tokens.roles().stream().map(Role::name).sorted().toList());
        }
    }
}
