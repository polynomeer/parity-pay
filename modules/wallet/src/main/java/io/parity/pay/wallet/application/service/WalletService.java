package io.parity.pay.wallet.application.service;

import io.parity.pay.ledger.application.port.in.LedgerBalanceQuery;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.port.in.WalletFundsUseCase;
import io.parity.pay.wallet.application.port.in.WalletQuery;
import io.parity.pay.wallet.application.port.out.WalletBalanceRepository;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.domain.Wallet;
import io.parity.pay.wallet.domain.WalletBalance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 지갑 조회와 원장 대사. 근거: FR-004, INV-010 */
@Service
public class WalletService implements WalletQuery, WalletFundsUseCase {

    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final LedgerBalanceQuery ledgerBalanceQuery;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;

    public WalletService(
            WalletRepository walletRepository,
            WalletBalanceRepository walletBalanceRepository,
            LedgerBalanceQuery ledgerBalanceQuery,
            ResolveLedgerAccountUseCase resolveLedgerAccount) {
        this.walletRepository = walletRepository;
        this.walletBalanceRepository = walletBalanceRepository;
        this.ledgerBalanceQuery = ledgerBalanceQuery;
        this.resolveLedgerAccount = resolveLedgerAccount;
    }

    @Override
    @Transactional(readOnly = true)
    public WalletBalanceView getBalance(MemberId memberId, WalletId walletId) {
        Wallet wallet = loadWallet(walletId);
        wallet.requireOwnedBy(memberId);
        WalletBalance balance = loadBalance(walletId);
        return new WalletBalanceView(
                walletId, balance.available(), balance.pending(), balance.updatedAt());
    }

    /**
     * 스냅샷과 원장 재생값을 비교합니다.
     *
     * <p>불일치는 스냅샷을 조용히 고치는 대신 사실대로 보고합니다. 보정은 원장 기준 재구축과 근거
     * 기록을 거칩니다. 근거: docs/09-consistency-recovery.md §9(F-010), §12
     */
    @Override
    @Transactional(readOnly = true)
    public BalanceVerification verifyAgainstLedger(WalletId walletId) {
        Wallet wallet = loadWallet(walletId);
        WalletBalance snapshot = loadBalance(walletId);

        LedgerAccount userPayMoney =
                resolveLedgerAccount.resolve(
                        AccountCode.USER_PAY_MONEY, walletId.value(), wallet.currency());
        Money ledgerBalance = ledgerBalanceQuery.balanceOf(userPayMoney.id());
        Money snapshotTotal = snapshot.ledgerEquivalent();

        return new BalanceVerification(
                walletId, snapshotTotal, ledgerBalance, snapshotTotal.equals(ledgerBalance));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SpendableWallet requireSpendable(
            WalletId walletId, MemberId memberId, CurrencyCode currency) {
        Wallet wallet = loadWallet(walletId);
        wallet.requireOwnedBy(memberId);
        wallet.requireSpendAllowed();
        wallet.requireCurrency(currency);
        return new SpendableWallet(wallet.id(), wallet.memberId(), wallet.currency());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(WalletId walletId, Money amount) {
        int updated = walletBalanceRepository.decreaseAvailableIfSufficient(walletId, amount);
        if (updated != 1) {
            // 잔액 부족입니다. 현재 잔액을 응답에 노출하지 않습니다. 근거: docs/04-payment-policy.md §10
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_BALANCE, "available balance is not sufficient");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(WalletId walletId, Money amount) {
        int updated = walletBalanceRepository.increaseAvailable(walletId, amount);
        if (updated != 1) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "wallet balance row missing for " + walletId);
        }
    }

    private Wallet loadWallet(WalletId walletId) {
        return walletRepository
                .findById(walletId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "wallet not found"));
    }

    private WalletBalance loadBalance(WalletId walletId) {
        return walletBalanceRepository
                .findByWalletId(walletId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERNAL_ERROR, "wallet balance row missing for " + walletId));
    }
}
