package io.parity.pay.wallet.application.port.out;

import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.wallet.domain.TopUp;
import java.util.Optional;

public interface TopUpRepository {

    Optional<TopUp> findById(TopUpId topUpId);

    TopUp save(TopUp topUp);
}
