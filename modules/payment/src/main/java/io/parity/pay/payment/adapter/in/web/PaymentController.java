package io.parity.pay.payment.adapter.in.web;

import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancellationView;
import io.parity.pay.payment.application.port.in.ConfirmOrderUseCase;
import io.parity.pay.payment.application.port.in.ConfirmOrderUseCase.OrderConfirmationView;
import io.parity.pay.payment.application.port.in.PaymentQuery;
import io.parity.pay.payment.domain.CancellationStatus;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** 결제·취소 API. 근거: docs/08-db-api-event-spec.md §4 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private final ApprovePaymentUseCase approvePayment;
    private final CancelPaymentUseCase cancelPayment;
    private final PaymentQuery paymentQuery;
    private final ConfirmOrderUseCase confirmOrder;
    private final CurrentPrincipal currentPrincipal;

    PaymentController(
            ApprovePaymentUseCase approvePayment,
            CancelPaymentUseCase cancelPayment,
            PaymentQuery paymentQuery,
            ConfirmOrderUseCase confirmOrder,
            CurrentPrincipal currentPrincipal) {
        this.approvePayment = approvePayment;
        this.cancelPayment = cancelPayment;
        this.paymentQuery = paymentQuery;
        this.confirmOrder = confirmOrder;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> approve(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ApprovePaymentRequest request) {
        PaymentView view = approvePayment.approve(new ApprovePaymentCommand(
                currentPrincipal.memberId(),
                request.orderId(),
                WalletId.of(request.walletId()),
                MerchantId.of(request.merchantId()),
                Money.of(request.amount(), CurrencyCode.valueOf(request.currency())),
                PaymentMethod.valueOf(request.method()),
                IdempotencyKey.of(idempotencyKey)));
        // 결과를 모르는 결제는 실패가 아닙니다. 202와 조회 위치를 주고 복구에 맡깁니다.
        // 여기서 4xx·5xx를 주면 클라이언트가 재시도해 이중 청구를 만듭니다. 근거: ADR-007
        if (view.status() == PaymentStatus.UNKNOWN) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/payments/" + view.paymentId().value()))
                    .body(PaymentResponse.from(view));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(view));
    }

    @GetMapping("/{paymentId}")
    ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(
                PaymentResponse.from(paymentQuery.getPayment(currentPrincipal.memberId(), PaymentId.of(paymentId))));
    }

    @PostMapping("/{paymentId}/cancellations")
    ResponseEntity<CancellationResponse> cancel(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID paymentId,
            @Valid @RequestBody CancelPaymentRequest request) {
        CancellationView view = cancelPayment.cancel(new CancelPaymentCommand(
                currentPrincipal.memberId(),
                PaymentId.of(paymentId),
                Money.of(request.amount(), CurrencyCode.valueOf(request.currency())),
                request.reason(),
                IdempotencyKey.of(idempotencyKey)));
        return ResponseEntity.status(HttpStatus.CREATED).body(CancellationResponse.from(view));
    }

    /**
     * 구매확정. 이 시점부터 판매자 정산 대상이 됩니다.
     *
     * <p>같은 결제를 여러 번 확정해도 정산 항목은 한 번만 만들어집니다.
     */
    @PostMapping("/{paymentId}/confirmation")
    ResponseEntity<OrderConfirmationResponse> confirm(@PathVariable UUID paymentId) {
        OrderConfirmationView view = confirmOrder.confirm(currentPrincipal.memberId(), PaymentId.of(paymentId));
        return ResponseEntity.ok(new OrderConfirmationResponse(
                view.paymentId().value(), view.orderId(), view.confirmedAt(), view.newlyConfirmed()));
    }

    record OrderConfirmationResponse(UUID paymentId, String orderId, Instant confirmedAt, boolean newlyConfirmed) {}

    record ApprovePaymentRequest(
            @NotBlank String orderId,
            @NotNull UUID walletId,
            @NotNull UUID merchantId,
            @Positive long amount,
            @NotNull String currency,
            @NotNull String method) {}

    record PaymentResponse(
            UUID paymentId,
            String orderId,
            PaymentStatus status,
            long requestedAmount,
            long approvedAmount,
            long canceledAmount,
            long cancellableAmount,
            String currency,
            Instant approvedAt) {

        static PaymentResponse from(PaymentView view) {
            return new PaymentResponse(
                    view.paymentId().value(),
                    view.orderId(),
                    view.status(),
                    view.requestedAmount().amount(),
                    view.approvedAmount().amount(),
                    view.canceledAmount().amount(),
                    view.cancellableAmount().amount(),
                    view.requestedAmount().currency().name(),
                    view.approvedAt());
        }
    }

    record CancelPaymentRequest(@Positive long amount, @NotNull String currency, String reason) {}

    record CancellationResponse(
            UUID cancellationId,
            UUID paymentId,
            CancellationStatus status,
            long requestedAmount,
            long completedAmount,
            long paymentCanceledAmount,
            String currency,
            Instant completedAt) {

        static CancellationResponse from(CancellationView view) {
            return new CancellationResponse(
                    view.cancellationId().value(),
                    view.paymentId().value(),
                    view.status(),
                    view.requestedAmount().amount(),
                    view.completedAmount().amount(),
                    view.paymentCanceledAmount().amount(),
                    view.requestedAmount().currency().name(),
                    view.completedAt());
        }
    }
}
