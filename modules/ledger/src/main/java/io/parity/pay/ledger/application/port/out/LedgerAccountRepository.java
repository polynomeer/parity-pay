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

    /**
     * 계정을 만들되, 같은 계정을 다른 트랜잭션이 방금 만들었으면 그것을 그대로 씁니다.
     *
     * <p>조회 후 없으면 삽입하는 방식은 동시 요청에서 둘 다 삽입을 시도해 유니크 제약에 걸립니다.
     * PostgreSQL에서 제약 위반은 트랜잭션 전체를 중단시키므로 잡아서 넘길 수도 없습니다. 삽입
     * 자체를 충돌 안전하게 만듭니다. 근거: docs/09-consistency-recovery.md §3, reports/11 P-004
     *
     * @return 새로 만들었거나 이미 있던 계정
     */
    LedgerAccount findOrCreate(LedgerAccount candidate);
}
