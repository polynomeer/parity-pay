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

    @Override
    public LedgerAccount findOrCreate(LedgerAccount candidate) {
        // computeIfAbsent가 아니라 find + put인 이유는 키가 계정 ID가 아니라 (코드, 통화, 소유자)
        // 조합이기 때문입니다. 맵 자체가 동시 접근에 안전하므로 여기서는 단순하게 둡니다.
        return find(candidate.code(), candidate.ownerId(), candidate.currency()).orElseGet(() -> save(candidate));
    }
}
