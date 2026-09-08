package io.parity.pay.api.mockpg;

import io.parity.pay.payment.application.port.out.PgRefundPort;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;
import org.springframework.stereotype.Component;

/**
 * Mock PG 환불 어댑터.
 *
 * <p>승인 어댑터와 같은 구조입니다. 환불도 결과를 모를 수 있고, 그때 재요청하면 이중 환불이
 * 됩니다. 근거: ADR-007, docs/10-test-strategy.md §6 (F-007)
 */
@Component
class MockPgRefundAdapter implements PgRefundPort {

    private final MockPgLedger mockPgLedger;
    private final MockPgBehavior behavior;

    MockPgRefundAdapter(MockPgLedger mockPgLedger, MockPgBehavior behavior) {
        this.mockPgLedger = mockPgLedger;
        this.behavior = behavior;
    }

    @Override
    public PgRefundResult refund(CancellationId cancellationId, PaymentId paymentId, Money amount) {
        String externalKey = cancellationId.toString();

        return switch (behavior.refundMode()) {
            case NORMAL -> PgRefundResult.refunded(mockPgLedger.refund(externalKey, paymentId.toString(), amount));
            case EXPLICIT_DECLINE -> {
                mockPgLedger.declineRefund(externalKey, paymentId.toString(), amount);
                yield PgRefundResult.declined("MOCK_PG_REFUND_DECLINED");
            }
            case TIMEOUT_BEFORE_APPROVAL -> throw new MockPgTimeoutException("mock pg timed out before refunding");
            case TIMEOUT_AFTER_APPROVAL -> {
                mockPgLedger.refund(externalKey, paymentId.toString(), amount);
                // 외부는 환불을 마쳤지만 응답이 유실됩니다(F-007). 고객은 이미 돈을 돌려받았습니다.
                throw new MockPgTimeoutException("mock pg timed out after refunding");
            }
        };
    }

    @Override
    public RefundStatus getStatus(CancellationId cancellationId) {
        if (!behavior.refundStatusQueryAvailable()) {
            return RefundStatus.UNAVAILABLE;
        }
        return mockPgLedger
                .refundStatusOf(cancellationId.toString())
                .map(status -> "REFUNDED".equals(status) ? RefundStatus.REFUNDED : RefundStatus.DECLINED)
                .orElse(RefundStatus.NOT_FOUND);
    }

    /** 외부 호출이 응답 없이 끝났다는 신호입니다. */
    static class MockPgTimeoutException extends RuntimeException {
        MockPgTimeoutException(String message) {
            super(message);
        }
    }
}
