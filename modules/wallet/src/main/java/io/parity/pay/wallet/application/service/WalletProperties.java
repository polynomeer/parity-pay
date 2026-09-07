package io.parity.pay.wallet.application.service;

import io.parity.pay.wallet.application.service.BalanceStrategySelector.Strategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 지갑 설정. */
@ConfigurationProperties(prefix = "paritypay.wallet")
public record WalletProperties(Strategy balanceStrategy) {

    public WalletProperties {
        balanceStrategy = balanceStrategy == null ? Strategy.CONDITIONAL_UPDATE : balanceStrategy;
    }
}
