package io.parity.pay.api.onboarding;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.money.Money;
import io.parity.pay.shared.security.CurrentPrincipal;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입·계좌 연결과 Mock Bank 동작 제어.
 *
 * <p>인증이 아직 없으므로 사용자 식별은 {@code X-Member-Id} 헤더로 대신합니다. Phase 2에서 인증을
 * 도입하면서 제거합니다. 근거: docs/13-implementation-checklist.md Phase 2
 */
@RestController
@RequestMapping("/api/v1")
class OnboardingController {

    private final OnboardingService onboardingService;
    private final MockBankBehavior mockBankBehavior;
    private final CurrentPrincipal currentPrincipal;

    OnboardingController(
            OnboardingService onboardingService,
            MockBankBehavior mockBankBehavior,
            CurrentPrincipal currentPrincipal) {
        this.onboardingService = onboardingService;
        this.mockBankBehavior = mockBankBehavior;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping("/members")
    ResponseEntity<RegisterMemberResponse> registerMember(
            @jakarta.validation.Valid @RequestBody RegisterMemberRequest request) {
        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterMemberResponse(
                        registered.memberId().value(), registered.walletId()));
    }

    @PostMapping("/bank-accounts")
    ResponseEntity<LinkBankAccountResponse> linkBankAccount(
            @jakarta.validation.Valid @RequestBody LinkBankAccountRequest request) {
        BankAccountId bankAccountId = onboardingService.linkBankAccount(
                currentPrincipal.memberId(),
                request.bankCode(),
                request.accountNumber(),
                Money.krw(request.initialBalance()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new LinkBankAccountResponse(bankAccountId.value()));
    }

    /**
     * 장애 시나리오 주입입니다. 운영 환경에는 존재하면 안 되는 기능이며, 최소한 운영자 권한으로
     * 막아 둡니다.
     */
    @PostMapping("/admin/mock-bank/mode")
    ResponseEntity<Void> setMockBankMode(@RequestBody MockBankModeRequest request) {
        mockBankBehavior.setMode(request.mode());
        return ResponseEntity.noContent().build();
    }

    record RegisterMemberRequest(
            @Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password) {}

    record RegisterMemberResponse(UUID memberId, UUID walletId) {}

    record LinkBankAccountRequest(
            @NotBlank String bankCode, @NotBlank String accountNumber, @Positive long initialBalance) {}

    record LinkBankAccountResponse(UUID bankAccountId) {}

    record MockBankModeRequest(MockBankBehavior.Mode mode) {}
}
