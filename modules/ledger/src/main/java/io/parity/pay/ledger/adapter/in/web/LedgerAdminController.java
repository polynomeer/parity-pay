package io.parity.pay.ledger.adapter.in.web;

import io.parity.pay.ledger.application.port.in.LedgerTransactionQuery;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.LedgerTransactionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원장 조회 API (운영자 전용).
 *
 * <p><b>수정하는 경로는 없습니다.</b> 확정 원장의 UPDATE·DELETE는 트리거가 거부하고(INV-006),
 * 보정은 새 분개로만 합니다(JE-012). 이 컨트롤러에 쓰기 메서드를 추가하지 마십시오.
 *
 * <p>권한은 `SecurityConfig`가 `/api/v1/admin/**`에 걸어 둔 규칙을 그대로 받습니다.
 * 근거: docs/15-ui-screen-plan.md §4.4, docs/16-ui-implementation-plan.md §4 FE-M5
 */
@RestController
@RequestMapping("/api/v1/admin/ledger")
class LedgerAdminController {

    private final LedgerTransactionQuery ledgerTransactionQuery;

    LedgerAdminController(LedgerTransactionQuery ledgerTransactionQuery) {
        this.ledgerTransactionQuery = ledgerTransactionQuery;
    }

    @GetMapping("/transactions/{transactionId}")
    ResponseEntity<LedgerTransactionResponse> getTransaction(@PathVariable UUID transactionId) {
        return ledgerTransactionQuery
                .findById(LedgerTransactionId.of(transactionId))
                .map(LedgerTransactionResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ledger transaction not found"));
    }

    record LedgerTransactionResponse(
            UUID transactionId,
            String referenceType,
            UUID referenceId,
            String transactionType,
            String currency,
            String status,
            UUID reversalOfTransactionId,
            Instant effectiveAt,
            List<EntryResponse> entries,
            long debitTotal,
            long creditTotal,
            boolean balanced) {

        static LedgerTransactionResponse from(LedgerTransactionQuery.LedgerTransactionView view) {
            return new LedgerTransactionResponse(
                    view.transactionId(),
                    view.referenceType(),
                    view.referenceId(),
                    view.transactionType(),
                    view.currency(),
                    view.status(),
                    view.reversalOfTransactionId(),
                    view.effectiveAt(),
                    view.entries().stream().map(EntryResponse::from).toList(),
                    view.debitTotal().amount(),
                    view.creditTotal().amount(),
                    view.balanced());
        }
    }

    record EntryResponse(UUID entryId, String accountCode, UUID ownerId, String direction, long amount) {

        static EntryResponse from(LedgerTransactionQuery.EntryView entry) {
            return new EntryResponse(
                    entry.entryId(),
                    entry.accountCode(),
                    entry.ownerId(),
                    entry.direction(),
                    entry.amount().amount());
        }
    }
}
