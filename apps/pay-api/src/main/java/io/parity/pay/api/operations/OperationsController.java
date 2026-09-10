package io.parity.pay.api.operations;

import io.parity.pay.api.merchant.Merchant;
import io.parity.pay.api.merchant.MerchantDirectory;
import io.parity.pay.api.observability.InvariantMetrics;
import io.parity.pay.api.outbox.OutboxAdminService;
import io.parity.pay.payment.application.service.PaymentRecoveryService;
import io.parity.pay.shared.id.PaymentId;
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
    private final TransactionSearchService searchService;
    private final InvariantMetrics invariantMetrics;
    private final TopUpRecoveryRepository recoveryRepository;
    private final RebuildBalanceUseCase rebuildBalance;
    private final PaymentRecoveryService paymentRecoveryService;
    private final MerchantDirectory merchantDirectory;
    private final OutboxAdminService outboxAdminService;
    private final ApprovalAuthority approvalAuthority;
    private final AuditLogWriter auditLogWriter;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentPrincipal currentPrincipal;

    OperationsController(
            TopUpRecoveryService recoveryService,
            TransactionTimelineService timelineService,
            TransactionSearchService searchService,
            InvariantMetrics invariantMetrics,
            TopUpRecoveryRepository recoveryRepository,
            RebuildBalanceUseCase rebuildBalance,
            PaymentRecoveryService paymentRecoveryService,
            MerchantDirectory merchantDirectory,
            OutboxAdminService outboxAdminService,
            ApprovalAuthority approvalAuthority,
            AuditLogWriter auditLogWriter,
            JdbcTemplate jdbcTemplate,
            CurrentPrincipal currentPrincipal) {
        this.recoveryService = recoveryService;
        this.timelineService = timelineService;
        this.searchService = searchService;
        this.invariantMetrics = invariantMetrics;
        this.recoveryRepository = recoveryRepository;
        this.rebuildBalance = rebuildBalance;
        this.paymentRecoveryService = paymentRecoveryService;
        this.merchantDirectory = merchantDirectory;
        this.outboxAdminService = outboxAdminService;
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

    /**
     * 식별자 해석. 근거: docs/16-ui-implementation-plan.md §4 FE-M4
     *
     * <p>운영자는 고객이 들고 온 값이 무엇인지 모릅니다. 결제 ID든 주문 ID든 지갑 ID든 이벤트
     * ID든 넣으면, 그것이 무엇인지와 타임라인을 열 수 있는 참조를 돌려줍니다.
     *
     * <p>타임라인과 나눈 이유는 지갑·회원이 거래 <b>여럿</b>을 가리키기 때문입니다. 하나로 합칠 수
     * 없는 것을 하나인 척하지 않습니다.
     */
    @GetMapping("/transactions/resolve")
    ResponseEntity<TransactionSearchService.SearchResult> resolve(@RequestParam String query) {
        return ResponseEntity.ok(searchService.resolve(query));
    }

    /**
     * 불변조건 현황. 근거: docs/15-ui-screen-plan.md §5.4
     *
     * <p>지금까지 이 값들은 Prometheus 지표로만 나갔습니다. 운영 콘솔이 읽을 수 있게 같은 캐시를
     * JSON으로도 엽니다. <b>다시 계산하지 않습니다</b> — 읽을 때마다 원장 전체를 집계하던 것이
     * 결함 G였습니다(reports/11 M-006).
     */
    @GetMapping("/invariants")
    ResponseEntity<InvariantMetrics.InvariantSnapshot> invariants() {
        return ResponseEntity.ok(invariantMetrics.snapshot());
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

    /**
     * 아직 최종 상태에 도달하지 못한 결제 목록입니다.
     *
     * <p>외부 PG 결제만 여기에 나타납니다. 페이머니 결제는 한 트랜잭션으로 끝나므로 미확정 상태로
     * 남지 않습니다.
     */
    @GetMapping("/payments")
    ResponseEntity<List<UnresolvedPaymentResponse>> listUnresolvedPayments(
            @RequestParam(defaultValue = "UNKNOWN") String status, @RequestParam(defaultValue = "50") int limit) {
        List<UnresolvedPaymentResponse> rows = jdbcTemplate.query(
                """
                SELECT p.payment_id, p.order_id, p.merchant_id, p.status, p.requested_amount, p.currency,
                       p.external_reference_id, p.created_at,
                       coalesce(r.attempt_count, 0) AS attempt_count,
                       coalesce(r.requires_manual_review, false) AS requires_manual_review,
                       r.last_error
                  FROM payment p
                  LEFT JOIN payment_recovery r ON r.payment_id = p.payment_id
                 WHERE p.status = ?
                 ORDER BY p.created_at
                 LIMIT ?
                """,
                (rs, rowNum) -> new UnresolvedPaymentResponse(
                        rs.getObject("payment_id", UUID.class),
                        rs.getString("order_id"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("status"),
                        rs.getLong("requested_amount"),
                        rs.getString("currency"),
                        rs.getString("external_reference_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("attempt_count"),
                        rs.getBoolean("requires_manual_review"),
                        rs.getString("last_error")),
                status,
                limit);
        return ResponseEntity.ok(rows);
    }

    /**
     * 결제의 외부 상태를 즉시 다시 조회해 확정을 시도합니다.
     *
     * <p>외부에 승인을 다시 보내는 것이 아니라 조회만 합니다. 이중 청구 위험이 없습니다.
     */
    @PostMapping("/payments/{paymentId}/resolve")
    ResponseEntity<PaymentRecoveryOutcomeResponse> resolvePayment(
            @PathVariable UUID paymentId, @Valid @RequestBody ResolveRequest request) {
        String operatorId = currentPrincipal.actorId();
        String beforeState = currentPaymentStatus(paymentId);
        PaymentRecoveryService.RecoveryOutcome outcome = paymentRecoveryService.resolveNow(PaymentId.of(paymentId));

        auditLogWriter.record(
                operatorId,
                "PAYMENT_RECOVERY_RESOLVE",
                "PAYMENT",
                paymentId.toString(),
                request.reason(),
                beforeState,
                outcome.status(),
                outcome.changed() ? AuditLogWriter.Result.SUCCEEDED : AuditLogWriter.Result.NO_CHANGE,
                outcome.detail());

        return ResponseEntity.ok(
                new PaymentRecoveryOutcomeResponse(paymentId, outcome.status(), outcome.changed(), outcome.detail()));
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
     * 스냅샷이 원장과 어긋난 지갑 목록입니다.
     *
     * <p>지표(`paritypay.invariant.balance_snapshot_drift`)는 <b>몇 개</b>인지만 알려 줍니다. 지갑이
     * 다섯 개일 때는 하나씩 확인해도 되지만 수천 개면 그럴 수 없고, 그래서 재구축 절차가 1단계에서
     * 막혀 있었습니다. 여기가 "어느 지갑인가"에 답하는 자리입니다.
     *
     * <p>고치지는 않습니다. 재구축은 지갑마다 사유와 승인자를 받아 따로 실행합니다 — 한 번에 전부
     * 맞추는 버튼은 만들지 않습니다. 조용히 맞춰버리면 원인을 조사할 증거가 사라지고, 그 판단은
     * 지갑 수가 많다고 해서 달라지지 않습니다.
     *
     * <p>이 질의는 원장 전체를 훑습니다(항목 200만 건에서 약 0.87초, M-006). 스크레이프가 아니라
     * 운영자가 필요할 때 부르는 경로이므로 그 비용을 여기서 냅니다.
     */
    @GetMapping("/wallets/balance-drift")
    ResponseEntity<List<BalanceDriftResponse>> listBalanceDrift(@RequestParam(defaultValue = "50") int limit) {
        List<BalanceDriftResponse> rows = jdbcTemplate.query(
                """
                SELECT wb.wallet_id,
                       wb.available_amount + wb.pending_amount AS snapshot_total,
                       coalesce(l.ledger_balance, 0) AS ledger_balance
                  FROM wallet_balance wb
                  JOIN ledger_account la
                    ON la.owner_id = wb.wallet_id AND la.account_code = '2010'
                  LEFT JOIN LATERAL (
                       SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount
                                                ELSE -e.amount END), 0) AS ledger_balance
                         FROM ledger_entry e
                        WHERE e.account_id = la.account_id) AS l ON true
                 WHERE wb.available_amount + wb.pending_amount <> coalesce(l.ledger_balance, 0)
                 ORDER BY abs(wb.available_amount + wb.pending_amount - coalesce(l.ledger_balance, 0)) DESC
                 LIMIT ?
                """,
                (rs, rowNum) -> new BalanceDriftResponse(
                        rs.getObject("wallet_id", UUID.class),
                        rs.getLong("snapshot_total"),
                        rs.getLong("ledger_balance"),
                        rs.getLong("snapshot_total") - rs.getLong("ledger_balance")),
                limit);
        return ResponseEntity.ok(rows);
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

    /**
     * Outbox 이벤트 목록입니다. 기본은 발행을 포기한 것들입니다.
     *
     * <p>적체 지표({@code paritypay.outbox.failed})가 0이 아닐 때 그 숫자의 정체를 보는 곳입니다.
     * 지금까지는 SQL로만 볼 수 있었습니다.
     */
    @GetMapping("/outbox-events")
    ResponseEntity<List<OutboxAdminService.OutboxEventSummary>> listOutboxEvents(
            @RequestParam(defaultValue = "FAILED") String status, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(outboxAdminService.list(status, limit));
    }

    /**
     * 적체가 몰려 있는 파티션 키입니다.
     *
     * <p>`paritypay.outbox.max_partition_pending`이 오르면 여기서 어느 지갑·결제인지 봅니다. 지표에
     * 파티션 키를 라벨로 붙이면 시계열이 무한히 늘어나므로 이 경로로 분리했습니다.
     */
    @GetMapping("/outbox-events/backlog")
    ResponseEntity<List<OutboxAdminService.PartitionBacklog>> outboxBacklog(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(outboxAdminService.partitionBacklog(limit));
    }

    /**
     * 실패한 이벤트를 다시 발행 대상으로 되돌립니다.
     *
     * <p>여기서 브로커로 직접 보내지 않습니다. 상태만 되돌리고 발행은 발행기가 합니다. 확인 시점과
     * 중복 규칙이 한 곳에만 있어야 하기 때문입니다.
     *
     * <p>같은 Aggregate의 뒤 이벤트가 이미 나갔으면 응답이 그 사실을 알려 줍니다. 되돌린 이벤트는
     * 순서가 뒤집힌 채 도착합니다. 근거: reports/11 M-001
     */
    @PostMapping("/outbox-events/{eventId}/retry")
    ResponseEntity<OutboxAdminService.RequeueOutcome> retryOutboxEvent(
            @PathVariable UUID eventId, @Valid @RequestBody ResolveRequest request) {
        String operatorId = currentPrincipal.actorId();
        OutboxAdminService.RequeueOutcome outcome = outboxAdminService.requeue(eventId);

        auditLogWriter.record(
                operatorId,
                "OUTBOX_EVENT_REQUEUE",
                "OUTBOX_EVENT",
                eventId.toString(),
                request.reason(),
                "status=FAILED attempts=" + outcome.previousAttemptCount(),
                "status=" + outcome.status(),
                outcome.changed() ? AuditLogWriter.Result.SUCCEEDED : AuditLogWriter.Result.NO_CHANGE,
                outcome.detail());

        return ResponseEntity.ok(outcome);
    }

    private String currentPaymentStatus(UUID paymentId) {
        List<String> rows =
                jdbcTemplate.queryForList("SELECT status FROM payment WHERE payment_id = ?", String.class, paymentId);
        return rows.isEmpty() ? null : rows.get(0);
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

    /** 스냅샷과 원장 재생값의 차이입니다. 부호가 있으므로 어느 쪽이 큰지 그대로 드러납니다. */
    record BalanceDriftResponse(UUID walletId, long snapshotTotal, long ledgerBalance, long difference) {}

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

    record PaymentRecoveryOutcomeResponse(UUID paymentId, String status, boolean changed, String detail) {}

    record UnresolvedPaymentResponse(
            UUID paymentId,
            String orderId,
            UUID merchantId,
            String status,
            long requestedAmount,
            String currency,
            String externalReferenceId,
            Instant createdAt,
            int attemptCount,
            boolean requiresManualReview,
            String lastError) {}
}
