package io.parity.pay.api.mockpg;

import io.parity.pay.payment.application.port.out.PgApprovalPort;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mock PG 승인 어댑터.
 *
 * <p>현재는 같은 프로세스 안의 대역입니다. 실제 네트워크 지연·연결 끊김까지 재현하려면 Mock Bank와
 * 함께 별도 프로세스로 분리해야 합니다. 근거: docs/13-implementation-checklist.md
 */
@Component
class MockPgApprovalAdapter implements PgApprovalPort {

    /**
     * Mock PG의 기관 식별자입니다. 고정값인 이유는 대역이 하나이기 때문이고, 고정이어야 재시작
     * 뒤에도 같은 PG 미수금 계정에 쌓입니다.
     */
    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final MockPgLedger mockPgLedger;
    private final MockPgBehavior behavior;

    MockPgApprovalAdapter(MockPgLedger mockPgLedger, MockPgBehavior behavior) {
        this.mockPgLedger = mockPgLedger;
        this.behavior = behavior;
    }

    @Override
    public UUID providerId() {
        return PROVIDER_ID;
    }

    @Override
    public PgApprovalResult approve(PaymentId paymentId, MerchantId merchantId, Money amount, String orderId) {
        String externalKey = paymentId.toString();

        return switch (behavior.mode()) {
            case NORMAL -> PgApprovalResult.approved(mockPgLedger.approve(externalKey, merchantId, orderId, amount));
            case EXPLICIT_DECLINE -> {
                mockPgLedger.decline(externalKey, merchantId, orderId, amount);
                yield PgApprovalResult.declined("MOCK_PG_DECLINED");
            }
                // 외부는 아무것도 하지 않았지만 내부는 그 사실을 알 수 없습니다.
            case TIMEOUT_BEFORE_APPROVAL -> throw new MockPgTimeoutException("mock pg timed out before approving");
            case TIMEOUT_AFTER_APPROVAL -> {
                mockPgLedger.approve(externalKey, merchantId, orderId, amount);
                // 외부는 승인을 마쳤지만 응답이 유실됩니다(F-006). 사용자는 이미 청구됐습니다.
                throw new MockPgTimeoutException("mock pg timed out after approving");
            }
        };
    }

    @Override
    public ApprovalStatus getStatus(PaymentId paymentId) {
        if (!behavior.statusQueryAvailable()) {
            // 조회 API 자체가 응답하지 않습니다. 결과를 "없음"으로 단정하면 안 됩니다(F-009).
            return ApprovalStatus.UNAVAILABLE;
        }
        return mockPgLedger
                .statusOf(paymentId.toString())
                .map(status -> "APPROVED".equals(status) ? ApprovalStatus.APPROVED : ApprovalStatus.DECLINED)
                .orElse(ApprovalStatus.NOT_FOUND);
    }

    /** 외부 호출이 응답 없이 끝났다는 신호입니다. 실패가 아니라 결과를 모르는 것입니다. */
    static class MockPgTimeoutException extends RuntimeException {
        MockPgTimeoutException(String message) {
            super(message);
        }
    }
}
