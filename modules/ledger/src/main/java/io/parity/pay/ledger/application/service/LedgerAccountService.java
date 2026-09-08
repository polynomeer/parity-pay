package io.parity.pay.ledger.application.service;

import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.application.port.out.LedgerAccountRepository;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.CurrencyCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원장 계정 조회·생성. 근거: docs/07-ledger-journal-catalog.md §3 */
@Service
public class LedgerAccountService implements ResolveLedgerAccountUseCase {

    private final LedgerAccountRepository accountRepository;

    public LedgerAccountService(LedgerAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public LedgerAccount resolve(AccountCode code, UUID ownerId, CurrencyCode currency) {
        return accountRepository
                .find(code, ownerId, currency)
                // 없으면 만들되, 같은 순간 다른 요청이 만들었을 수 있으므로 충돌 안전하게 만듭니다.
                // 계정은 첫 사용 시점에 생기므로 이 경합은 부하가 시작되는 순간에 실제로 일어납니다.
                .orElseGet(() -> accountRepository.findOrCreate(
                        new LedgerAccount(LedgerAccountId.generate(), code, ownerId, currency, true)));
    }

    @Override
    @Transactional
    public LedgerAccount resolveCorporate(AccountCode code, CurrencyCode currency) {
        return resolve(code, null, currency);
    }
}
