package io.parity.pay.api.mockpg;

import io.parity.pay.payment.application.port.out.PgApprovalPort;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 외부 PG 승인 어댑터.
 *
 * <p>기관은 다른 프로세스에 있고 호출은 HTTP입니다. 응답을 받지 못하면 결과를 모르는 것이며, 그
 * 예외를 여기서 잡지 않습니다 — `UNKNOWN` 보존은 호출하는 서비스의 일입니다. 근거: ADR-007
 */
@Component
class MockPgApprovalAdapter implements PgApprovalPort {

    /**
     * 이 PG의 기관 식별자입니다.
     *
     * <p>고정값인 이유는 대역이 하나이기 때문이고, 고정이어야 재시작 뒤에도 같은 PG 미수금 계정에
     * 쌓입니다.
     */
    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final MockPgClient client;

    MockPgApprovalAdapter(MockPgClient client) {
        this.client = client;
    }

    @Override
    public UUID providerId() {
        return PROVIDER_ID;
    }

    @Override
    public PgApprovalResult approve(PaymentId paymentId, MerchantId merchantId, Money amount, String orderId) {
        MockPgClient.ResultResponse response =
                client.approve(paymentId.toString(), merchantId.value(), orderId, amount.amount());
        return response.succeeded()
                ? PgApprovalResult.approved(response.externalReferenceId())
                : PgApprovalResult.declined(response.failureReason());
    }

    @Override
    public ApprovalStatus getStatus(PaymentId paymentId) {
        try {
            return client.approvalStatus(paymentId.toString())
                    .map(status -> "APPROVED".equals(status) ? ApprovalStatus.APPROVED : ApprovalStatus.DECLINED)
                    .orElse(ApprovalStatus.NOT_FOUND);
        } catch (PgUnknownResultException e) {
            // 물어보지 못한 것과 "기록 없음"은 다릅니다. 후자로 취급하면 조회 장애가 곧 "청구되지
            // 않았다"는 결론이 됩니다. 근거: docs/09-consistency-recovery.md §7
            return ApprovalStatus.UNAVAILABLE;
        }
    }
}
