package io.parity.pay.api.merchant;

import io.parity.pay.settlement.application.port.in.SettlementQuery;
import io.parity.pay.settlement.application.port.in.SettlementView;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.SettlementId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 API.
 *
 * <p>판매자는 자기 정산만 봅니다. 조회 대상은 토큰에서 결정되고 요청에는 판매자 식별자를 넣는
 * 자리가 없습니다. 다른 판매자의 정산을 지정하면 403이 아니라 404입니다. 없는 것과 볼 수 없는 것을
 * 구분해 주면 어떤 정산이 존재하는지 알려주는 셈이 됩니다.
 *
 * <p>운영자 경로(`/api/v1/admin/settlements`)와 다릅니다. 그쪽은 계산·지급·보류까지 하고 모든
 * 판매자를 봅니다. 판매자에게는 읽기만 있습니다.
 *
 * <p>근거: FR-016, docs/02-prd.md §6
 */
@RestController
@RequestMapping("/api/v1/merchant")
class MerchantController {

    private final MerchantDirectory merchantDirectory;
    private final SettlementQuery settlements;

    MerchantController(MerchantDirectory merchantDirectory, SettlementQuery settlements) {
        this.merchantDirectory = merchantDirectory;
        this.settlements = settlements;
    }

    /** 내 판매자 정보입니다. 자기 merchantId를 확인하는 유일한 경로입니다. */
    @GetMapping("/me")
    ResponseEntity<MerchantResponse> me() {
        Merchant merchant = merchantDirectory.requireCurrentMerchant();
        return ResponseEntity.ok(
                new MerchantResponse(merchant.id().value(), merchant.name(), merchant.status(), merchant.createdAt()));
    }

    @GetMapping("/settlements")
    ResponseEntity<List<SettlementResponse>> listSettlements(@RequestParam(defaultValue = "20") int limit) {
        Merchant merchant = merchantDirectory.requireCurrentMerchant();
        return ResponseEntity.ok(settlements.listByMerchant(merchant.id(), limit).stream()
                .map(SettlementResponse::from)
                .toList());
    }

    @GetMapping("/settlements/{settlementId}")
    ResponseEntity<SettlementResponse> getSettlement(@PathVariable UUID settlementId) {
        Merchant merchant = merchantDirectory.requireCurrentMerchant();
        SettlementView view = settlements.get(SettlementId.of(settlementId));
        if (!view.merchantId().equals(merchant.id())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "settlement not found");
        }
        return ResponseEntity.ok(SettlementResponse.from(view));
    }

    record MerchantResponse(UUID merchantId, String name, String status, Instant createdAt) {}

    record SettlementResponse(
            UUID settlementId,
            LocalDate periodStart,
            LocalDate periodEnd,
            long grossAmount,
            long cancellationAmount,
            long feeAmount,
            long adjustmentAmount,
            long netAmount,
            String currency,
            String status,
            Instant paidAt) {

        /**
         * 판매자 응답에는 외부 지급 참조를 넣지 않습니다. 우리 은행 거래 식별자이고 판매자가 할 수
         * 있는 일도 없습니다. 문의는 지급 상태와 회차 번호로 충분합니다.
         */
        static SettlementResponse from(SettlementView view) {
            return new SettlementResponse(
                    view.settlementId().value(),
                    view.periodStart(),
                    view.periodEnd(),
                    view.grossAmount().amount(),
                    view.cancellationAmount().amount(),
                    view.feeAmount().amount(),
                    view.adjustmentAmount(),
                    view.netAmount().amount(),
                    view.netAmount().currency().name(),
                    view.status().name(),
                    view.paidAt());
        }
    }
}
