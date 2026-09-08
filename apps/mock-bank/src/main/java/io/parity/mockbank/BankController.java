package io.parity.mockbank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이 기관의 API.
 *
 * <p>우리 도메인 타입을 쓰지 않습니다. 금액은 정수, 식별자는 문자열입니다. 외부기관은 우리 타입을
 * 모릅니다.
 *
 * <p>멱등 키는 호출자가 보냅니다(`externalKey`). 같은 키의 재요청에 기관은 같은 결과를 답합니다.
 */
@RestController
@RequestMapping("/mock-bank")
class BankController {

    private static final Logger log = LoggerFactory.getLogger(BankController.class);

    private final BankLedger ledger;
    private final BankBehavior behavior;

    BankController(BankLedger ledger, BankBehavior behavior) {
        this.ledger = ledger;
        this.behavior = behavior;
    }

    @PostMapping("/accounts")
    ResponseEntity<Void> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        ledger.openAccount(
                UUID.fromString(request.accountId()),
                request.accountNumberToken(),
                request.balance(),
                request.currency());
        return ResponseEntity.noContent().build();
    }

    /**
     * 출금.
     *
     * <p>모드에 따라 응답하지 않고 붙잡고 있을 수 있습니다. 그때 호출자는 **진짜 읽기 타임아웃**을
     * 겪습니다. 예외를 던져 흉내 내던 것과 다릅니다.
     */
    @PostMapping("/withdrawals")
    ResponseEntity<TransferResponse> withdraw(@Valid @RequestBody WithdrawalRequest request) {
        return switch (behavior.withdrawalMode()) {
            case NORMAL -> ResponseEntity.ok(
                    toResponse(ledger.withdraw(request.accountNumberToken(), request.amount(), request.externalKey())));
            case EXPLICIT_FAILURE -> ResponseEntity.ok(new TransferResponse(false, null, "MOCK_BANK_DECLINED"));
            case HANG_BEFORE_PROCESSING -> {
                log.info("hanging before processing withdrawal {}", request.externalKey());
                behavior.hang();
                yield ResponseEntity.ok(new TransferResponse(false, null, "MOCK_BANK_LATE_RESPONSE"));
            }
            case HANG_AFTER_PROCESSING -> {
                // 자금은 움직였고 응답만 늦습니다. 호출자는 결과를 모른 채 타임아웃을 겪습니다.
                ledger.withdraw(request.accountNumberToken(), request.amount(), request.externalKey());
                log.info("hanging after processing withdrawal {}", request.externalKey());
                behavior.hang();
                yield ResponseEntity.ok(new TransferResponse(true, request.externalKey(), null));
            }
        };
    }

    @GetMapping("/withdrawals/{externalKey}")
    ResponseEntity<StatusResponse> withdrawalStatus(@PathVariable String externalKey) {
        if (!behavior.withdrawalStatusQueryAvailable()) {
            // 조회 API 자체가 죽어 있습니다. 호출자는 "없음"으로 단정하면 안 됩니다(F-009).
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(
                new StatusResponse(ledger.withdrawalStatus(externalKey).orElse("NOT_FOUND")));
    }

    @PostMapping("/payouts")
    ResponseEntity<TransferResponse> payout(@Valid @RequestBody PayoutRequest request) {
        return switch (behavior.payoutMode()) {
            case NORMAL -> ResponseEntity.ok(toResponse(
                    ledger.payout(request.externalKey(), UUID.fromString(request.merchantId()), request.amount())));
            case EXPLICIT_FAILURE -> ResponseEntity.ok(new TransferResponse(false, null, "MOCK_BANK_PAYOUT_DECLINED"));
            case HANG_BEFORE_PROCESSING -> {
                behavior.hang();
                yield ResponseEntity.ok(new TransferResponse(false, null, "MOCK_BANK_LATE_RESPONSE"));
            }
            case HANG_AFTER_PROCESSING -> {
                ledger.payout(request.externalKey(), UUID.fromString(request.merchantId()), request.amount());
                behavior.hang();
                yield ResponseEntity.ok(new TransferResponse(true, request.externalKey(), null));
            }
        };
    }

    @GetMapping("/payouts/{externalKey}")
    ResponseEntity<StatusResponse> payoutStatus(@PathVariable String externalKey) {
        if (!behavior.payoutStatusQueryAvailable()) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(
                new StatusResponse(ledger.payoutStatus(externalKey).orElse("NOT_FOUND")));
    }

    /** 장애 주입입니다. 이 앱은 운영에 배포되지 않으므로 인증을 두지 않습니다. */
    @PostMapping("/admin/behavior")
    ResponseEntity<Void> setBehavior(@RequestBody BehaviorRequest request) {
        if (request.withdrawalMode() != null) {
            behavior.setWithdrawalMode(request.withdrawalMode());
        }
        if (request.payoutMode() != null) {
            behavior.setPayoutMode(request.payoutMode());
        }
        if (request.withdrawalStatusQueryAvailable() != null) {
            behavior.setWithdrawalStatusQueryAvailable(request.withdrawalStatusQueryAvailable());
        }
        if (request.payoutStatusQueryAvailable() != null) {
            behavior.setPayoutStatusQueryAvailable(request.payoutStatusQueryAvailable());
        }
        if (request.hangForMillis() != null) {
            behavior.setHangFor(Duration.ofMillis(request.hangForMillis()));
        }
        if (Boolean.TRUE.equals(request.reset())) {
            behavior.reset();
        }
        return ResponseEntity.noContent().build();
    }

    private static TransferResponse toResponse(BankLedger.Outcome outcome) {
        return new TransferResponse(outcome.succeeded(), outcome.externalReferenceId(), outcome.failureReason());
    }

    record OpenAccountRequest(
            @NotBlank String accountId,
            @NotBlank String accountNumberToken,
            @Positive long balance,
            @NotBlank String currency) {}

    record WithdrawalRequest(
            @NotBlank String externalKey, @NotBlank String accountNumberToken, @Positive long amount) {}

    record PayoutRequest(@NotBlank String externalKey, @NotBlank String merchantId, @Positive long amount) {}

    record TransferResponse(boolean succeeded, String externalReferenceId, String failureReason) {}

    record StatusResponse(String status) {}

    record BehaviorRequest(
            BankBehavior.Mode withdrawalMode,
            BankBehavior.Mode payoutMode,
            Boolean withdrawalStatusQueryAvailable,
            Boolean payoutStatusQueryAvailable,
            Long hangForMillis,
            Boolean reset) {}
}
