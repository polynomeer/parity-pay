package io.parity.pay.api.onboarding;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.mockpg.MockPgBehavior;
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
 * <p>사용자 식별은 {@code CurrentPrincipal}을 통해 서명된 토큰에서만 옵니다. 가입과 토큰 발급은
 * 인증 이전 경로이므로 열려 있습니다. 근거: docs/13-implementation-checklist.md Phase 7
 */
@RestController
@RequestMapping("/api/v1")
class OnboardingController {

    private final OnboardingService onboardingService;
    private final MockBankBehavior mockBankBehavior;
    private final MockPgBehavior mockPgBehavior;
    private final CurrentPrincipal currentPrincipal;

    OnboardingController(
            OnboardingService onboardingService,
            MockBankBehavior mockBankBehavior,
            MockPgBehavior mockPgBehavior,
            CurrentPrincipal currentPrincipal) {
        this.onboardingService = onboardingService;
        this.mockBankBehavior = mockBankBehavior;
        this.mockPgBehavior = mockPgBehavior;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping("/members")
    ResponseEntity<RegisterMemberResponse> registerMember(
            @jakarta.validation.Valid @RequestBody RegisterMemberRequest request) {
        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterMemberResponse(registered.memberId().value(), registered.walletId()));
    }

    @PostMapping("/bank-accounts")
    ResponseEntity<LinkBankAccountResponse> linkBankAccount(
            @jakarta.validation.Valid @RequestBody LinkBankAccountRequest request) {
        BankAccountId bankAccountId = onboardingService.linkBankAccount(
                currentPrincipal.memberId(),
                request.bankCode(),
                request.accountNumber(),
                Money.krw(request.initialBalance()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new LinkBankAccountResponse(bankAccountId.value()));
    }

    /**
     * 장애 시나리오 주입입니다. 운영 환경에는 존재하면 안 되는 기능이며, 최소한 운영자 권한으로
     * 막아 둡니다.
     *
     * <p>기관이 다른 프로세스에 있으므로 이 호출은 기관에게 전달됩니다. 우리 안의 스위치를
     * 바꾸는 것이 아닙니다.
     */
    @PostMapping("/admin/mock-bank/mode")
    ResponseEntity<Void> setMockBankMode(@RequestBody MockBankModeRequest request) {
        mockBankBehavior.setMode(request.mode());
        return ResponseEntity.noContent().build();
    }

    /** Mock PG의 장애 주입입니다. Mock Bank와 같은 이유로 운영자 권한 아래 둡니다. */
    @PostMapping("/admin/mock-pg/mode")
    ResponseEntity<Void> setMockPgMode(@RequestBody MockPgModeRequest request) {
        mockPgBehavior.setMode(request.mode());
        if (request.statusQueryAvailable() != null) {
            mockPgBehavior.setStatusQueryAvailable(request.statusQueryAvailable());
        }
        return ResponseEntity.noContent().build();
    }

    record RegisterMemberRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password) {}

    record RegisterMemberResponse(UUID memberId, UUID walletId) {}

    record LinkBankAccountRequest(
            @NotBlank @Size(max = 10) String bankCode,
            // 길이를 제한하지 않으면 저장 단계에서 500으로 실패합니다. 입력 문제는 입력에서 막습니다.
            @NotBlank @Size(min = 8, max = 30) String accountNumber,
            @Positive long initialBalance) {}

    record LinkBankAccountResponse(UUID bankAccountId) {}

    record MockBankModeRequest(MockBankBehavior.Mode mode) {}

    record MockPgModeRequest(MockPgBehavior.Mode mode, Boolean statusQueryAvailable) {}
}
