package io.parity.pay.settlement.adapter.in.web;

import io.parity.pay.settlement.application.port.in.SettlementUseCases.SettlementView;
import io.parity.pay.settlement.application.service.SettlementPayoutService;
import io.parity.pay.settlement.application.service.SettlementService;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정산 API.
 *
 * <p>계산·지급·보류는 운영 작업이므로 관리자 경로에 둡니다. 조회는 판매자도 사용할 수 있어야 하지만
 * 인증이 없으므로 현재는 모두 관리자 경로입니다(Phase 2 잔여 작업).
 */
@RestController
@RequestMapping("/api/v1/admin/settlements")
class SettlementController {

    private final SettlementService settlementService;
    private final SettlementPayoutService payoutService;

    SettlementController(SettlementService settlementService, SettlementPayoutService payoutService) {
        this.settlementService = settlementService;
        this.payoutService = payoutService;
    }

    @PostMapping
    ResponseEntity<SettlementResponse> calculate(@Valid @RequestBody CalculateRequest request) {
        SettlementView view = settlementService.calculate(
                MerchantId.of(request.merchantId()), request.periodStart(), request.periodEnd());
        return ResponseEntity.status(HttpStatus.CREATED).body(SettlementResponse.from(view));
    }

    @PostMapping("/{settlementId}/payouts")
    ResponseEntity<SettlementResponse> pay(@PathVariable UUID settlementId) {
        SettlementView view = payoutService.pay(SettlementId.of(settlementId));
        HttpStatus status = switch (view.status()) {
            case PAID -> HttpStatus.OK;
            // 결과를 모르는 지급은 실패가 아닙니다. 조회 위치를 알려주고 복구에 맡깁니다.
            case UNKNOWN, PAYING -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(SettlementResponse.from(view));
    }

    @PostMapping("/{settlementId}/hold")
    ResponseEntity<SettlementResponse> hold(
            @PathVariable UUID settlementId, @Valid @RequestBody HoldRequest request) {
        return ResponseEntity.ok(SettlementResponse.from(
                settlementService.hold(SettlementId.of(settlementId), request.reason())));
    }

    @PostMapping("/{settlementId}/release")
    ResponseEntity<SettlementResponse> release(@PathVariable UUID settlementId) {
        return ResponseEntity.ok(
                SettlementResponse.from(settlementService.release(SettlementId.of(settlementId))));
    }

    @GetMapping("/{settlementId}")
    ResponseEntity<SettlementResponse> get(@PathVariable UUID settlementId) {
        return ResponseEntity.ok(
                SettlementResponse.from(settlementService.get(SettlementId.of(settlementId))));
    }

    @GetMapping
    ResponseEntity<List<SettlementResponse>> listByMerchant(
            @RequestParam UUID merchantId, @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                settlementService.listByMerchant(MerchantId.of(merchantId), limit).stream()
                        .map(SettlementResponse::from)
                        .toList());
    }

    record CalculateRequest(
            @NotNull UUID merchantId, @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd) {}

    record HoldRequest(@NotBlank String reason) {}

    record SettlementResponse(
            UUID settlementId,
            UUID merchantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            long grossAmount,
            long cancellationAmount,
            long feeAmount,
            long adjustmentAmount,
            long netAmount,
            String currency,
            String status,
            String externalReferenceId,
            Instant paidAt) {

        static SettlementResponse from(SettlementView view) {
            return new SettlementResponse(
                    view.settlementId().value(),
                    view.merchantId().value(),
                    view.periodStart(),
                    view.periodEnd(),
                    view.grossAmount().amount(),
                    view.cancellationAmount().amount(),
                    view.feeAmount().amount(),
                    view.adjustmentAmount(),
                    view.netAmount().amount(),
                    view.netAmount().currency().name(),
                    view.status().name(),
                    view.externalReferenceId(),
                    view.paidAt());
        }
    }
}
