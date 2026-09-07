package io.parity.pay.api.operations;

import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.security.CurrentPrincipal;
import io.parity.pay.wallet.application.port.out.TopUpRecoveryRepository;
import io.parity.pay.wallet.application.service.TopUpRecoveryService;
import io.parity.pay.wallet.application.service.TopUpRecoveryService.RecoveryOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 API.
 *
 * <p>미확정 거래를 찾고, 안전한 범위에서 재조회를 요청합니다. 여기에서 금액을 직접 고치는 기능은
 * 제공하지 않습니다. 보정은 원장 분개로만 가능합니다. 근거: docs/09-consistency-recovery.md §13
 *
 * <p>인증이 아직 없으므로 운영자 식별은 {@code X-Operator-Id} 헤더로 대신합니다. 역할 기반 권한은
 * Phase 2 잔여 작업입니다.
 */
@RestController
@RequestMapping("/api/v1/admin")
class OperationsController {

    private final TopUpRecoveryService recoveryService;
    private final TransactionTimelineService timelineService;
    private final TopUpRecoveryRepository recoveryRepository;
    private final AuditLogWriter auditLogWriter;
    private final JdbcTemplate jdbcTemplate;
    private final CurrentPrincipal currentPrincipal;

    OperationsController(
            TopUpRecoveryService recoveryService,
            TransactionTimelineService timelineService,
            TopUpRecoveryRepository recoveryRepository,
            AuditLogWriter auditLogWriter,
            JdbcTemplate jdbcTemplate,
            CurrentPrincipal currentPrincipal) {
        this.recoveryService = recoveryService;
        this.timelineService = timelineService;
        this.recoveryRepository = recoveryRepository;
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

    private String currentStatus(UUID topUpId) {
        List<String> rows =
                jdbcTemplate.queryForList("SELECT status FROM top_up WHERE top_up_id = ?", String.class, topUpId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    record ResolveRequest(@NotBlank String reason) {}

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
