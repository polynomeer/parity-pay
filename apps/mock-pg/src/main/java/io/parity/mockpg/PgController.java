package io.parity.mockpg;

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
 * 이 PG의 API.
 *
 * <p>우리 도메인 타입을 쓰지 않습니다. 금액은 정수, 식별자는 문자열입니다. 멱등 키는 호출자가
 * 보내며(`externalKey`), 같은 키의 재요청에는 같은 결과를 답합니다.
 */
@RestController
@RequestMapping("/mock-pg")
class PgController {

    private static final Logger log = LoggerFactory.getLogger(PgController.class);

    private final PgLedger ledger;
    private final PgBehavior behavior;

    PgController(PgLedger ledger, PgBehavior behavior) {
        this.ledger = ledger;
        this.behavior = behavior;
    }

    @PostMapping("/approvals")
    ResponseEntity<ResultResponse> approve(@Valid @RequestBody ApprovalRequest request) {
        UUID merchantId = UUID.fromString(request.merchantId());
        return switch (behavior.approvalMode()) {
            case NORMAL -> ResponseEntity.ok(new ResultResponse(
                    true,
                    ledger.approve(request.externalKey(), merchantId, request.orderId(), request.amount()),
                    null));
            case EXPLICIT_DECLINE -> {
                ledger.decline(request.externalKey(), merchantId, request.orderId(), request.amount());
                yield ResponseEntity.ok(new ResultResponse(false, null, "MOCK_PG_DECLINED"));
            }
            case HANG_BEFORE_PROCESSING -> {
                log.info("hanging before approving {}", request.externalKey());
                behavior.hang();
                yield ResponseEntity.ok(new ResultResponse(false, null, "MOCK_PG_LATE_RESPONSE"));
            }
            case HANG_AFTER_PROCESSING -> {
                // 카드는 이미 청구됐고 응답만 늦습니다. 호출자는 결과를 모릅니다.
                String reference =
                        ledger.approve(request.externalKey(), merchantId, request.orderId(), request.amount());
                log.info("hanging after approving {}", request.externalKey());
                behavior.hang();
                yield ResponseEntity.ok(new ResultResponse(true, reference, null));
            }
        };
    }

    @GetMapping("/approvals/{externalKey}")
    ResponseEntity<StatusResponse> approvalStatus(@PathVariable String externalKey) {
        if (!behavior.approvalStatusQueryAvailable()) {
            // 조회 API만 죽어 있습니다. 호출자는 "기록 없음"으로 단정하면 안 됩니다(F-009).
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(
                new StatusResponse(ledger.approvalStatus(externalKey).orElse("NOT_FOUND")));
    }

    @PostMapping("/refunds")
    ResponseEntity<ResultResponse> refund(@Valid @RequestBody RefundRequest request) {
        return switch (behavior.refundMode()) {
            case NORMAL -> ResponseEntity.ok(new ResultResponse(
                    true, ledger.refund(request.externalKey(), request.paymentKey(), request.amount()), null));
            case EXPLICIT_DECLINE -> {
                ledger.declineRefund(request.externalKey(), request.paymentKey(), request.amount());
                yield ResponseEntity.ok(new ResultResponse(false, null, "MOCK_PG_REFUND_DECLINED"));
            }
            case HANG_BEFORE_PROCESSING -> {
                behavior.hang();
                yield ResponseEntity.ok(new ResultResponse(false, null, "MOCK_PG_LATE_RESPONSE"));
            }
            case HANG_AFTER_PROCESSING -> {
                // 환불은 이미 나갔고 응답만 늦습니다(F-007).
                String reference = ledger.refund(request.externalKey(), request.paymentKey(), request.amount());
                behavior.hang();
                yield ResponseEntity.ok(new ResultResponse(true, reference, null));
            }
        };
    }

    @GetMapping("/refunds/{externalKey}")
    ResponseEntity<StatusResponse> refundStatus(@PathVariable String externalKey) {
        if (!behavior.refundStatusQueryAvailable()) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(
                new StatusResponse(ledger.refundStatus(externalKey).orElse("NOT_FOUND")));
    }

    /** 장애 주입입니다. 이 앱은 운영에 배포되지 않으므로 인증을 두지 않습니다. */
    /**
     * 시험이 기관의 장부를 초기화하는 통로입니다.
     *
     * <p>기관이 자기 데이터베이스를 갖게 되면서 시험이 우리 JdbcTemplate으로 기관 표를 비울 수 없게
     * 됐습니다. 그건 옳은 일이고, 대신 기관 쪽에 이 통로를 둡니다.
     */
    @PostMapping("/admin/reset")
    ResponseEntity<Void> reset() {
        ledger.reset();
        behavior.reset();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/{table}/count")
    ResponseEntity<Long> count(
            @PathVariable String table,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
        return ResponseEntity.ok(ledger.count(table, status));
    }

    @PostMapping("/admin/behavior")
    ResponseEntity<Void> setBehavior(@RequestBody BehaviorRequest request) {
        if (request.approvalMode() != null) {
            behavior.setApprovalMode(request.approvalMode());
        }
        if (request.refundMode() != null) {
            behavior.setRefundMode(request.refundMode());
        }
        if (request.approvalStatusQueryAvailable() != null) {
            behavior.setApprovalStatusQueryAvailable(request.approvalStatusQueryAvailable());
        }
        if (request.refundStatusQueryAvailable() != null) {
            behavior.setRefundStatusQueryAvailable(request.refundStatusQueryAvailable());
        }
        if (request.hangForMillis() != null) {
            behavior.setHangFor(Duration.ofMillis(request.hangForMillis()));
        }
        if (Boolean.TRUE.equals(request.reset())) {
            behavior.reset();
        }
        return ResponseEntity.noContent().build();
    }

    record ApprovalRequest(
            @NotBlank String externalKey,
            @NotBlank String merchantId,
            @NotBlank String orderId,
            @Positive long amount) {}

    record RefundRequest(@NotBlank String externalKey, @NotBlank String paymentKey, @Positive long amount) {}

    record ResultResponse(boolean succeeded, String externalReferenceId, String failureReason) {}

    record StatusResponse(String status) {}

    record BehaviorRequest(
            PgBehavior.Mode approvalMode,
            PgBehavior.Mode refundMode,
            Boolean approvalStatusQueryAvailable,
            Boolean refundStatusQueryAvailable,
            Long hangForMillis,
            Boolean reset) {}
}
