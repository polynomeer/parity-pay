package io.parity.pay.payment.application.port.out;

import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.shared.id.CancellationId;
import java.util.Optional;

public interface PaymentCancellationRepository {

    Optional<PaymentCancellation> findById(CancellationId cancellationId);

    PaymentCancellation save(PaymentCancellation cancellation);
}
