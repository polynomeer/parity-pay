package io.parity.pay.api.security;

import io.parity.pay.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final CurrentPrincipal currentPrincipal;

    AuthController(AuthenticationService authenticationService, CurrentPrincipal currentPrincipal) {
        this.authenticationService = authenticationService;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping("/tokens")
    ResponseEntity<TokenResponse> issue(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(
                TokenResponse.from(authenticationService.login(request.email(), request.password())));
    }

    @PostMapping("/tokens/refresh")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(
                TokenResponse.from(authenticationService.refresh(request.refreshToken())));
    }

    /** 이 사용자의 리프레시 토큰을 모두 철회합니다. 액세스 토큰은 만료로 사라집니다. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        authenticationService.logout(currentPrincipal.memberId());
        return ResponseEntity.noContent().build();
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {}

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
