package io.parity.pay.reconciliation.adapter.in.web;

import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.reconciliation.application.service.MismatchResolutionService;
import io.parity.pay.reconciliation.application.service.ReconciliationService;
import io.parity.pay.reconciliation.application.service.ReconciliationService.ReconciliationSummary;
import io.parity.pay.reconciliation.domain.MismatchType;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import io.parity.pay.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대사 운영 API.
 *
 * <p>금액을 직접 고치는 엔드포인트는 없습니다. 보정은 근거(불일치 ID), 사유, 요청자와 승인자를
 * 갖춘 원장 분개로만 가능합니다. 근거: docs/09-consistency-recovery.md §13
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final MismatchResolutionService resolutionService;
    private final CurrentPrincipal currentPrincipal;

    ReconciliationController(
            ReconciliationService reconciliationService,
            MismatchResolutionService resolutionService,
            CurrentPrincipal currentPrincipal) {
        this.reconciliationService = reconciliationService;
        this.resolutionService = resolutionService;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping("/runs")
    ResponseEntity<RunResponse> run() {
        ReconciliationSummary summary = reconciliationService.runAll();
        return ResponseEntity.ok(new RunResponse(
                summary.runId(),
                summary.internalCount(),
                summary.externalCount(),
                summary.mismatchCount()));
    }

    @GetMapping("/mismatches")
    ResponseEntity<List<MismatchResponse>> listOpen(
            @RequestParam(required = false) MismatchType type,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(
                resolutionService.findOpen(type, limit).stream().map(MismatchResponse::from).toList());
    }

    /** 조사 결과 조치가 필요 없을 때 사용합니다. 사유는 필수입니다. */
    @PostMapping("/mismatches/{mismatchId}/resolve")
    ResponseEntity<MismatchResponse> resolve(
            @PathVariable UUID mismatchId, @Valid @RequestBody ResolveRequest request) {
        ReconciliationMismatch resolved = resolutionService.resolveWithoutAdjustment(
                mismatchId, currentPrincipal.actorId(), request.reason(), request.ignore());
        return ResponseEntity.ok(MismatchResponse.from(resolved));
    }

    /**
     * 보정 분개로 해결합니다.
     *
     * <p>요청자는 인증된 운영자이고, 승인자는 {@code X-Approver-Id}로 지정합니다. 승인자는 실제로
     * 존재하는 {@code OPS_APPROVER}여야 하며 요청자와 달라야 합니다. 한 사람이 원장을 임의로
     * 움직일 수 없게 하는 최소 통제입니다.
     */
    @PostMapping("/mismatches/{mismatchId}/adjustments")
    ResponseEntity<MismatchResponse> adjust(
            @RequestHeader("X-Approver-Id") String approvedBy,
            @PathVariable UUID mismatchId,
            @Valid @RequestBody AdjustmentRequest request) {
        ReconciliationMismatch resolved = resolutionService.resolveWithAdjustment(
                mismatchId,
                currentPrincipal.actorId(),
                approvedBy,
                request.reason(),
                request.debitAccount(),
                request.creditAccount(),
                request.debitOwnerId(),
                request.creditOwnerId(),
                request.amount());
        return ResponseEntity.ok(MismatchResponse.from(resolved));
    }

    record RunResponse(UUID runId, int internalCount, int externalCount, int mismatchCount) {}

    record ResolveRequest(@NotBlank String reason, boolean ignore) {}

    record AdjustmentRequest(
            @NotBlank String reason,
            @NotNull AccountCode debitAccount,
            @NotNull AccountCode creditAccount,
            UUID debitOwnerId,
            UUID creditOwnerId,
            @Positive long amount) {}

    record MismatchResponse(
            UUID mismatchId,
            String type,
            String referenceType,
            String referenceId,
            Long internalAmount,
            Long externalAmount,
            long amountDifference,
            String detail,
            String resolutionStatus,
            String resolvedBy,
            String resolutionReason,
            UUID adjustmentLedgerTransactionId,
            Instant detectedAt,
            Instant resolvedAt) {

        static MismatchResponse from(ReconciliationMismatch mismatch) {
            return new MismatchResponse(
                    mismatch.mismatchId(),
                    mismatch.type().name(),
                    mismatch.referenceType(),
                    mismatch.referenceId(),
                    mismatch.internalAmount(),
                    mismatch.externalAmount(),
                    mismatch.amountDifference(),
                    mismatch.detail(),
                    mismatch.resolutionStatus().name(),
                    mismatch.resolvedBy(),
                    mismatch.resolutionReason(),
                    mismatch.adjustmentLedgerTransactionId() == null
                            ? null
                            : mismatch.adjustmentLedgerTransactionId().value(),
                    mismatch.detectedAt(),
                    mismatch.resolvedAt());
        }
    }
}
