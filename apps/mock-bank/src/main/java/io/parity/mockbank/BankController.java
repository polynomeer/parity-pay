package io.parity.mockbank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 대사용 출금 명세입니다.
     *
     * <p>지금까지 우리 쪽 대사는 이 기관의 표를 같은 데이터베이스에서 직접 읽었습니다. 그러면
     * 기관이 명세를 주지 못하는 상황이 아예 표현되지 않습니다 — 조회는 언제나 성공하고, 결과가
     * 비어 있으면 "기관에 기록이 없다"가 됩니다. 실제 대사는 파일이나 API로 받으며, 못 받는 날이
     * 있습니다.
     */
    @GetMapping("/statements/withdrawals")
    ResponseEntity<List<BankLedger.Statement>> withdrawalStatement(
            @RequestParam Instant from, @RequestParam Instant to) {
        if (!behavior.statementAvailable()) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(ledger.withdrawalStatement(from, to));
    }

    /** 대사용 지급 명세입니다. */
    @GetMapping("/statements/payouts")
    ResponseEntity<List<BankLedger.Statement>> payoutStatement(@RequestParam Instant from, @RequestParam Instant to) {
        if (!behavior.statementAvailable()) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(ledger.payoutStatement(from, to));
    }

    /** 건별 기록입니다. 상태만이 아니라 금액·시각까지 알려 줍니다. */
    @GetMapping("/records/withdrawals/{externalKey}")
    ResponseEntity<BankLedger.Statement> withdrawalRecord(@PathVariable String externalKey) {
        if (!behavior.withdrawalStatusQueryAvailable()) {
            return ResponseEntity.status(503).build();
        }
        return ledger.withdrawalRecord(externalKey).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    @GetMapping("/records/payouts/{externalKey}")
    ResponseEntity<BankLedger.Statement> payoutRecord(@PathVariable String externalKey) {
        if (!behavior.payoutStatusQueryAvailable()) {
            return ResponseEntity.status(503).build();
        }
        return ledger.payoutRecord(externalKey).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    /**
     * 시험이 기관의 장부를 손보는 통로입니다.
     *
     * <p>기관이 자기 데이터베이스를 갖게 되면서 시험이 우리 JdbcTemplate으로 기관 표를 비우거나
     * 고칠 수 없게 됐습니다. 그건 옳은 일이고, 대신 기관 쪽에 이 통로를 둡니다. 이 앱은 운영에
     * 배포되지 않습니다.
     */
    @PostMapping("/admin/reset")
    ResponseEntity<Void> reset() {
        ledger.reset();
        behavior.reset();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/withdrawals/amend")
    ResponseEntity<Void> amendWithdrawal(@RequestBody AmendRequest request) {
        ledger.amendWithdrawal(request.externalKey(), request.amount(), Boolean.TRUE.equals(request.delete()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/withdrawals/orphan")
    ResponseEntity<Void> insertOrphanWithdrawal(@RequestBody OrphanRequest request) {
        ledger.insertOrphanWithdrawal(request.externalKey(), request.amount(), request.accountNumberToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/accounts/{accountNumberToken}/balance")
    ResponseEntity<Long> accountBalance(@PathVariable String accountNumberToken) {
        return ledger.accountBalance(accountNumberToken)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/admin/accounts/balance-total")
    ResponseEntity<Long> totalBalance() {
        return ResponseEntity.ok(ledger.totalAccountBalance());
    }

    @GetMapping("/admin/{table}/count")
    ResponseEntity<Long> count(@PathVariable String table, @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ledger.count(table, status));
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
        if (request.statementAvailable() != null) {
            behavior.setStatementAvailable(request.statementAvailable());
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

    record AmendRequest(String externalKey, Long amount, Boolean delete) {}

    record OrphanRequest(String externalKey, long amount, String accountNumberToken) {}

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
            Boolean statementAvailable,
            Long hangForMillis,
            Boolean reset) {}
}
