package io.parity.pay.wallet.adapter.in.web;

import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.shared.security.CurrentPrincipal;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpView;
import io.parity.pay.wallet.domain.TopUpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 충전 API. 근거: docs/08-db-api-event-spec.md §4
 *
 * <p>결과가 불명확하면 실패로 응답하지 않고 {@code 202 Accepted}와 조회 위치를 제공합니다.
 * 근거: docs/04-payment-policy.md §10, ADR-007
 */
@RestController
@RequestMapping("/api/v1/top-ups")
class TopUpController {

    private final RequestTopUpUseCase requestTopUp;
    private final CurrentPrincipal currentPrincipal;

    TopUpController(RequestTopUpUseCase requestTopUp, CurrentPrincipal currentPrincipal) {
        this.requestTopUp = requestTopUp;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    ResponseEntity<TopUpResponse> requestTopUp(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody TopUpRequest request) {

        TopUpView view = requestTopUp.requestTopUp(new TopUpCommand(
                currentPrincipal.memberId(),
                WalletId.of(request.walletId()),
                BankAccountId.of(request.bankAccountId()),
                Money.of(request.amount(), CurrencyCode.valueOf(request.currency())),
                IdempotencyKey.of(idempotencyKey)));

        TopUpResponse body = TopUpResponse.from(view);
        return switch (view.status()) {
            case SUCCEEDED -> ResponseEntity.status(HttpStatus.CREATED).body(body);
            case FAILED -> ResponseEntity.status(HttpStatus.OK).body(body);
                // PROCESSING·UNKNOWN은 아직 결과를 모르는 상태입니다. 실패로 단정하지 않습니다.
            case REQUESTED, PROCESSING, UNKNOWN -> ResponseEntity.accepted()
                    .location(URI.create("/api/v1/top-ups/" + view.topUpId()))
                    .body(body);
        };
    }

    @GetMapping("/{topUpId}")
    ResponseEntity<TopUpResponse> getTopUp(@PathVariable UUID topUpId) {
        TopUpView view = requestTopUp.getTopUp(currentPrincipal.memberId(), TopUpId.of(topUpId));
        return ResponseEntity.ok(TopUpResponse.from(view));
    }

    record TopUpRequest(
            @NotNull UUID walletId, @NotNull UUID bankAccountId, @Positive long amount, @NotNull String currency) {}

    record TopUpResponse(
            UUID topUpId,
            TopUpStatus status,
            long requestedAmount,
            long completedAmount,
            String currency,
            Instant requestedAt,
            Instant completedAt) {

        static TopUpResponse from(TopUpView view) {
            return new TopUpResponse(
                    view.topUpId().value(),
                    view.status(),
                    view.requestedAmount().amount(),
                    view.completedAmount().amount(),
                    view.requestedAmount().currency().name(),
                    view.requestedAt(),
                    view.completedAt());
        }
    }
}
