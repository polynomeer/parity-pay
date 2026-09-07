package io.parity.pay.wallet.application.port.out;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.wallet.domain.Wallet;
import java.util.Optional;

public interface WalletRepository {

    Optional<Wallet> findById(WalletId walletId);

    Optional<Wallet> findByMemberAndCurrency(MemberId memberId, CurrencyCode currency);

    Wallet save(Wallet wallet);
}
