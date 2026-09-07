package io.parity.pay.ledger.application.port.out;

import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.CurrencyCode;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository {

    Optional<LedgerAccount> find(AccountCode code, UUID ownerId, CurrencyCode currency);

    Optional<LedgerAccount> findById(LedgerAccountId id);

    LedgerAccount save(LedgerAccount account);
}
