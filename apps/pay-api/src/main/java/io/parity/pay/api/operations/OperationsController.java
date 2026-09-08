package io.parity.pay.api.operations;

import io.parity.pay.api.merchant.Merchant;
import io.parity.pay.api.merchant.MerchantDirectory;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.security.ApprovalAuthority;
import io.parity.pay.shared.security.CurrentPrincipal;
import io.parity.pay.wallet.application.port.in.RebuildBalanceUseCase;
import io.parity.pay.wallet.application.port.in.RebuildBalanceUseCase.RebuildOutcome;
import io.parity.pay.wallet.application.port.out.TopUpRecoveryRepository;
import io.parity.pay.wallet.application.service.TopUpRecoveryService;
import io.parity.pay.wallet.application.service.TopUpRecoveryService.RecoveryOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 API.
 *
 * <p>미확정 거래를 찾고, 안전한 범위에서 재조회를 요청합니다. 여기에서 금액을 직접 고치는 기능은
 * 제공하지 않습니다. 보정은 원장 분개로만 가능합니다. 근거: docs/09-consistency-recovery.md §13
 *
 * <p>운영자 식별은 서명된 토큰에서만 옵니다. 경로별 역할은 {@code SecurityConfig}가 강제하고,
 * 이중 승인이 필요한 경로는 승인자를 {@code X-Approver-Id}로 따로 받습니다.
 */
@RestController
@RequestMapping("/api/v1/admin")
class OperationsController {

    private final TopUpRecoveryService recoveryService;
    private final TransactionTimelineService timelineService;
    private final TopUpRecoveryRepository recoveryRepository;
    private final RebuildBalanceUseCase rebuildBalance;
    private final MerchantDirectory merchantDirectory;
    private final ApprovalAuthority approvalAuthority;
    private final AuditLogWriter auditLogWriter;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentPrincipal currentPrincipal;

    OperationsController(
            TopUpRecoveryService recoveryService,
            TransactionTimelineService timelineService,
            TopUpRecoveryRepository recoveryRepository,
            RebuildBalanceUseCase rebuildBalance,
            MerchantDirectory merchantDirectory,
            ApprovalAuthority approvalAuthority,
            AuditLogWriter auditLogWriter,
            JdbcTemplate jdbcTemplate,
            CurrentPrincipal currentPrincipal) {
        this.recoveryService = recoveryService;
        this.timelineService = timelineService;
        this.recoveryRepository = recoveryRepository;
        this.rebuildBalance = rebuildBalance;
        this.merchantDirectory = merchantDirectory;
        this.approvalAuthority = approvalAuthority;
        this.auditLogWriter = auditLogWriter;
        this.jdbcTemplate = jdbcTemplate;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 통합 거래 타임라인. 근거: FR-012, DoD-07
     *
     * <p>주문 ID, 결제 ID, 충전 ID 등 무엇을 넣어도 관련된 업무·원장·외부·이벤트·운영 기록이
     * 시간순으로 나옵니다.
     */
    @GetMapping("/transactions/{referenceId}/timeline")
    ResponseEntity<TransactionTimelineService.Timeline> timeline(@PathVariable String referenceId) {
        return ResponseEntity.ok(timelineService.of(referenceId));
    }

    /** 아직 최종 상태에 도달하지 못한 충전 목록입니다. */
    @GetMapping("/top-ups")
    ResponseEntity<List<UnresolvedTopUpResponse>> listUnresolved(
            @RequestParam(defaultValue = "UNKNOWN") String status, @RequestParam(defaultValue = "50") int limit) {
        List<UnresolvedTopUpResponse> rows = jdbcTemplate.query(
                """
                SELECT t.top_up_id, t.wallet_id, t.status, t.requested_amount, t.currency,
                       t.requested_at,
                       coalesce(r.attempt_count, 0) AS attempt_count,
                       coalesce(r.requires_manual_review, false) AS requires_manual_review,
                       r.last_error
                  FROM top_up t
                  LEFT JOIN top_up_recovery r ON r.top_up_id = t.top_up_id
                 WHERE t.status = ?
                 ORDER BY t.requested_at
                 LIMIT ?
                """,
                (rs, rowNum) -> new UnresolvedTopUpResponse(
                        rs.getObject("top_up_id", UUID.class),
                        rs.getObject("wallet_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("requested_amount"),
                        rs.getString("currency"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getInt("attempt_count"),
                        rs.getBoolean("requires_manual_review"),
                        rs.getString("last_error")),
                status,
                limit);
        return ResponseEntity.ok(rows);
    }

    /** 자동 복구를 포기하고 사람에게 넘어온 건들입니다. */
    @GetMapping("/recovery/manual-review")
    ResponseEntity<List<Map<String, Object>>> listManualReview(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = recoveryRepository.findManualReview(limit).stream()
                .map(item -> Map.<String, Object>of(
                        "topUpId", item.topUpId().toString(),
                        "status", item.status().name(),
                        "attemptCount", item.attemptCount()))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /**
     * 외부 상태를 즉시 다시 조회해 확정을 시도합니다.
     *
     * <p>외부에 승인 요청을 다시 보내는 것이 아니라 조회만 합니다. 중복 승인 위험이 없습니다.
     */
    @PostMapping("/top-ups/{topUpId}/resolve")
    ResponseEntity<RecoveryOutcomeResponse> resolve(
            @PathVariable UUID topUpId, @Valid @RequestBody ResolveRequest request) {
        String operatorId = currentPrincipal.actorId();
        String beforeState = currentStatus(topUpId);
        RecoveryOutcome outcome = recoveryService.resolveNow(TopUpId.of(topUpId));

        auditLogWriter.record(
                operatorId,
                "TOP_UP_RECOVERY_RESOLVE",
                "TOP_UP",
                topUpId.toString(),
                request.reason(),
                beforeState,
                outcome.status(),
                outcome.changed() ? AuditLogWriter.Result.SUCCEEDED : AuditLogWriter.Result.NO_CHANGE,
                outcome.detail());

        return ResponseEntity.ok(
                new RecoveryOutcomeResponse(topUpId, outcome.status(), outcome.changed(), outcome.detail()));
    }

    /**
     * 잔액 스냅샷을 원장으로 재구축합니다. 근거: INV-010, ADR-008, reports/11 F-010
     *
     * <p>고치는 것은 스냅샷 한 줄이고 원장은 읽기만 합니다. 쓸 수 있는 값도 원장 계산값 하나뿐이라
     * 이 경로로 없는 돈을 만들 수 없습니다.
     *
     * <p>그래도 승인자를 요구합니다. 재구축은 어긋난 값을 사라지게 만들어 원인 조사의 증거를
     * 지웁니다. 무엇을 왜 지웠는지 두 사람이 알고 있어야 하고, 감사 로그에 전후 값이 남습니다.
     */
    @PostMapping("/wallets/{walletId}/balance-rebuild")
    ResponseEntity<BalanceRebuildResponse> rebuildBalance(
            @RequestHeader("X-Approver-Id") String approvedBy,
            @PathVariable UUID walletId,
            @Valid @RequestBody RebuildRequest request) {
        String operatorId = currentPrincipal.actorId();
        approvalAuthority.requireDistinctApprover(operatorId, approvedBy);

        RebuildOutcome outcome = rebuildBalance.rebuildFromLedger(WalletId.of(walletId));

        auditLogWriter.record(
                operatorId,
                "WALLET_BALANCE_REBUILD",
                "WALLET",
                walletId.toString(),
                request.reason(),
                "snapshot=" + outcome.snapshotBefore().amount() + " ledger="
                        + outcome.ledger().amount(),
                "snapshot=" + outcome.snapshotAfter().amount(),
                outcome.changed() ? AuditLogWriter.Result.SUCCEEDED : AuditLogWriter.Result.NO_CHANGE,
                "approvedBy=" + approvedBy + " " + outcome.detail());

        return ResponseEntity.ok(new BalanceRebuildResponse(
                walletId,
                outcome.snapshotBefore().amount(),
                outcome.ledger().amount(),
                outcome.snapshotAfter().amount(),
                outcome.status().name(),
                outcome.detail()));
    }

    /**
     * 회원을 판매자로 등록합니다.
     *
     * <p>가입한 사람이 스스로 판매자가 될 수는 없습니다. 판매자로 등록되면 정산 금액이 보이므로
     * 운영자가 확인하고 등록합니다. 감사 로그에 누가 누구를 등록했는지 남습니다.
     */
    @PostMapping("/merchants")
    ResponseEntity<MerchantRegistrationResponse> registerMerchant(@Valid @RequestBody RegisterMerchantRequest request) {
        String operatorId = currentPrincipal.actorId();
        Merchant merchant = merchantDirectory.register(request.ownerEmail(), request.name());

        auditLogWriter.record(
                operatorId,
                "MERCHANT_REGISTER",
                "MERCHANT",
                merchant.id().toString(),
                request.reason(),
                null,
                "owner=" + request.ownerEmail(),
                AuditLogWriter.Result.SUCCEEDED,
                "name=" + merchant.name());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MerchantRegistrationResponse(
                        merchant.id().value(), merchant.name(), request.ownerEmail(), merchant.status()));
    }

    private String currentStatus(UUID topUpId) {
        List<String> rows =
                jdbcTemplate.queryForList("SELECT status FROM top_up WHERE top_up_id = ?", String.class, topUpId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    record ResolveRequest(@NotBlank String reason) {}

    record RebuildRequest(@NotBlank String reason) {}

    record RegisterMerchantRequest(
            @NotBlank @Size(max = 100) String name, @NotBlank String ownerEmail, @NotBlank String reason) {}

    record MerchantRegistrationResponse(UUID merchantId, String name, String ownerEmail, String status) {}

    record BalanceRebuildResponse(
            UUID walletId, long snapshotBefore, long ledgerBalance, long snapshotAfter, String status, String detail) {}

    record UnresolvedTopUpResponse(
            UUID topUpId,
            UUID walletId,
            String status,
            long requestedAmount,
            String currency,
            Instant requestedAt,
            int attemptCount,
            boolean requiresManualReview,
            String lastError) {}

    record RecoveryOutcomeResponse(UUID topUpId, String status, boolean changed, String detail) {}
}
