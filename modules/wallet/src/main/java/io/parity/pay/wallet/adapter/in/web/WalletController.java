package io.parity.pay.wallet.adapter.in.web;

import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.security.CurrentPrincipal;
import io.parity.pay.wallet.application.port.in.WalletQuery;
import io.parity.pay.wallet.application.port.in.WalletTransactionQuery;
import io.parity.pay.wallet.domain.WalletTransactionEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 지갑 조회 API. 근거: FR-004 */
@RestController
@RequestMapping("/api/v1/wallets")
class WalletController {

    private final WalletQuery walletQuery;
    private final WalletTransactionQuery walletTransactionQuery;
    private final CurrentPrincipal currentPrincipal;

    WalletController(
            WalletQuery walletQuery, WalletTransactionQuery walletTransactionQuery, CurrentPrincipal currentPrincipal) {
        this.walletQuery = walletQuery;
        this.walletTransactionQuery = walletTransactionQuery;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 호출자 자신의 지갑입니다. 근거: FR-004
     *
     * <p>클라이언트가 `walletId`를 보관하지 않아도 됩니다. 이 경로가 없던 동안 `walletId`는 가입
     * 응답에만 있었고 토큰에도 없어서, 다른 기기에서 로그인하면 자기 지갑을 조회할 수단이
     * 없었습니다. 프론트엔드를 붙이면서 드러났습니다.
     */
    @GetMapping("/me")
    ResponseEntity<WalletBalanceResponse> getMyBalance(@RequestParam(defaultValue = "KRW") String currency) {
        WalletQuery.WalletBalanceView view =
                walletQuery.getMyBalance(currentPrincipal.memberId(), CurrencyCode.valueOf(currency));
        return ResponseEntity.ok(toResponse(view));
    }

    @GetMapping("/{walletId}")
    ResponseEntity<WalletBalanceResponse> getBalance(@PathVariable UUID walletId) {
        WalletQuery.WalletBalanceView view = walletQuery.getBalance(currentPrincipal.memberId(), WalletId.of(walletId));
        return ResponseEntity.ok(toResponse(view));
    }

    private static WalletBalanceResponse toResponse(WalletQuery.WalletBalanceView view) {
        return new WalletBalanceResponse(
                view.walletId().value(),
                view.available().amount(),
                view.pending().amount(),
                view.available().currency().name(),
                view.asOf());
    }

    /**
     * 거래내역을 커서로 조회합니다. 근거: FR-008
     *
     * <p>내역은 이벤트로 만들어지는 프로젝션이므로 방금 만든 거래가 아주 잠깐 보이지 않을 수
     * 있습니다. 잔액과 결제 상태는 동기 API로 즉시 확인할 수 있습니다.
     */
    @GetMapping("/{walletId}/transactions")
    ResponseEntity<TransactionPageResponse> getTransactions(
            @PathVariable UUID walletId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        WalletTransactionQuery.TransactionPage page =
                walletTransactionQuery.list(currentPrincipal.memberId(), WalletId.of(walletId), cursor, limit);
        return ResponseEntity.ok(new TransactionPageResponse(
                page.entries().stream().map(TransactionResponse::from).toList(), page.nextCursor()));
    }

    /**
     * 스냅샷과 원장 재생값을 비교합니다. 운영자가 INV-010 위반을 확인하는 진입점입니다.
     *
     * <p>회원 경로 아래 있지만 운영자 권한을 요구합니다({@code SecurityConfig}). 본인 지갑이라도
     * 열지 않는 이유는 불일치를 다루는 절차가 전부 운영자 몫이기 때문입니다.
     */
    @GetMapping("/{walletId}/ledger-verification")
    ResponseEntity<BalanceVerificationResponse> verify(@PathVariable UUID walletId) {
        WalletQuery.BalanceVerification verification = walletQuery.verifyAgainstLedger(WalletId.of(walletId));
        return ResponseEntity.ok(new BalanceVerificationResponse(
                verification.walletId().value(),
                verification.snapshot().amount(),
                verification.ledger().amount(),
                verification.matches()));
    }

    record TransactionPageResponse(List<TransactionResponse> transactions, String nextCursor) {}

    record TransactionResponse(
            UUID transactionId,
            String type,
            String direction,
            long amount,
            String currency,
            String referenceType,
            String referenceId,
            Instant occurredAt) {

        static TransactionResponse from(WalletTransactionEntry entry) {
            return new TransactionResponse(
                    entry.transactionId(),
                    entry.type().name(),
                    entry.direction().name(),
                    entry.amount().amount(),
                    entry.amount().currency().name(),
                    entry.referenceType(),
                    entry.referenceId(),
                    entry.occurredAt());
        }
    }

    record WalletBalanceResponse(UUID walletId, long available, long pending, String currency, Instant asOf) {}

    record BalanceVerificationResponse(UUID walletId, long snapshotBalance, long ledgerBalance, boolean matches) {}
}
