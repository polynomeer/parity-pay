package io.parity.pay.ledger.testing;

import io.parity.pay.ledger.application.port.out.LedgerAccountRepository;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.CurrencyCode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 단위 테스트용 인메모리 계정 저장소. */
public class InMemoryLedgerAccountRepository implements LedgerAccountRepository {

    private final Map<UUID, LedgerAccount> accounts = new ConcurrentHashMap<>();

    @Override
    public Optional<LedgerAccount> find(AccountCode code, UUID ownerId, CurrencyCode currency) {
        return accounts.values().stream()
                .filter(account -> account.code() == code
                        && account.currency() == currency
                        && Objects.equals(account.ownerId(), ownerId))
                .findFirst();
    }

    @Override
    public Optional<LedgerAccount> findById(LedgerAccountId id) {
        return Optional.ofNullable(accounts.get(id.value()));
    }

    @Override
    public LedgerAccount save(LedgerAccount account) {
        accounts.put(account.id().value(), account);
        return account;
    }
}
