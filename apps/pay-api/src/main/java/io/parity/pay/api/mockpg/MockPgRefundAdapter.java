package io.parity.pay.api.mockpg;

import io.parity.pay.payment.application.port.out.PgRefundPort;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;
import org.springframework.stereotype.Component;

/**
 * 외부 PG 환불 어댑터.
 *
 * <p>승인과 같은 규칙입니다. 환불도 결과를 모를 수 있고, 그때 재요청하면 이중 환불이 됩니다.
 * 근거: ADR-007, docs/10-test-strategy.md §6 (F-007)
 */
@Component
class MockPgRefundAdapter implements PgRefundPort {

    private final MockPgClient client;

    MockPgRefundAdapter(MockPgClient client) {
        this.client = client;
    }

    @Override
    public PgRefundResult refund(CancellationId cancellationId, PaymentId paymentId, Money amount) {
        MockPgClient.ResultResponse response =
                client.refund(cancellationId.toString(), paymentId.toString(), amount.amount());
        return response.succeeded()
                ? PgRefundResult.refunded(response.externalReferenceId())
                : PgRefundResult.declined(response.failureReason());
    }

    @Override
    public RefundStatus getStatus(CancellationId cancellationId) {
        try {
            return client.refundStatus(cancellationId.toString())
                    .map(status -> "REFUNDED".equals(status) ? RefundStatus.REFUNDED : RefundStatus.DECLINED)
                    .orElse(RefundStatus.NOT_FOUND);
        } catch (PgUnknownResultException e) {
            return RefundStatus.UNAVAILABLE;
        }
    }
}
