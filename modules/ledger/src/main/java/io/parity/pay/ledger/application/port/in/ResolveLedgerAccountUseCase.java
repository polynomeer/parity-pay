package io.parity.pay.ledger.application.port.in;

import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.money.CurrencyCode;
import java.util.UUID;

/**
 * 계정 코드와 소유자로 원장 계정을 조회하고, 없으면 생성합니다.
 *
 * <p>업무 모듈은 계정 ID를 직접 만들지 않고 이 포트를 통해 얻습니다.
 */
public interface ResolveLedgerAccountUseCase {

    /** 소유자 차원이 있는 계정 (예: 지갑별 2010, 판매자별 2030). */
    LedgerAccount resolve(AccountCode code, UUID ownerId, CurrencyCode currency);

    /** 소유자가 하나뿐인 법인 계정 (예: 1010). */
    LedgerAccount resolveCorporate(AccountCode code, CurrencyCode currency);
}
