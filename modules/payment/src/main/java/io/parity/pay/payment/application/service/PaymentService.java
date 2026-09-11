package io.parity.pay.payment.application.service;

import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.PaymentQuery;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.application.port.out.PgApprovalPort;
import io.parity.pay.payment.application.port.out.PgApprovalPort.PgApprovalResult;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.idempotency.RequestHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인.
 *
 * <p>수단에 따라 경로가 갈립니다. 페이머니는 외부 호출이 없어 한 트랜잭션으로 끝나고, 외부 PG는
 * 요청을 먼저 커밋한 뒤 외부를 호출하고 결과를 따로 확정합니다({@link PaymentTransactions}).
 * 같은 메서드 안에서 분기하는 이유는 호출자에게는 같은 유스케이스이기 때문입니다.
 *
 * <p>페이머니 경로에서는 다음이 하나의 PostgreSQL 트랜잭션에서 처리됩니다.
 *
 * <ol>
 *   <li>멱등성 레코드 선점
 *   <li>지갑 상태·소유권 확인
 *   <li>조건부 잔액 차감 (INV-003)
 *   <li>결제 분개 전기 JE-003 (INV-001)
 *   <li>결제 상태 확정과 멱등 응답 저장
 * </ol>
 *
 * <p>어느 단계든 실패하면 모두 롤백됩니다. 근거: docs/05-technical-design.md §7
 */
@Service
public class PaymentService implements ApprovePaymentUseCase, PaymentQuery {

    static final String OPERATION = "PAYMENT";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentTransactions transactions;
    private final PgApprovalPort pgApprovalPort;

    public PaymentService(
            PaymentRepository paymentRepository, PaymentTransactions transactions, PgApprovalPort pgApprovalPort) {
        this.paymentRepository = paymentRepository;
        this.transactions = transactions;
        this.pgApprovalPort = pgApprovalPort;
    }

    @Override
    public PaymentView approve(ApprovePaymentCommand command) {
        if (command.method().callsExternalProvider()) {
            return approveThroughProvider(command);
        }
        return PaymentView.of(transactions.approveWithPayMoney(command, canonicalHash(command)));
    }

    /**
     * 외부 PG 승인.
     *
     * <p>외부 호출은 어떤 트랜잭션에도 속하지 않습니다. 예외를 실패로 단정하지 않고 결과를 모르는
     * 상태로 보존합니다. 근거: ADR-007
     */
    private PaymentView approveThroughProvider(ApprovePaymentCommand command) {
        String requestHash = canonicalHash(command);
        PaymentTransactions.Started started = transactions.begin(command, requestHash);
        if (!started.isNew()) {
            log.info(
                    "idempotent replay for payment {} (status={})",
                    started.payment().id(),
                    started.payment().status());
            return PaymentView.of(started.payment());
        }

        Payment payment = started.payment();
        PgApprovalResult result;
        try {
            result = pgApprovalPort.approve(
                    payment.id(), payment.merchantId(), payment.requestedAmount(), payment.orderId());
        } catch (RuntimeException e) {
            log.warn("pg approval outcome is unknown for payment {}", payment.id(), e);
            result = PgApprovalResult.unknown(null);
        }

        Payment settled =
                switch (result.outcome()) {
                    case APPROVED -> transactions.completeApproved(
                            command.memberId(), payment, result.externalReferenceId());
                    case DECLINED -> transactions.completeDeclined(command.memberId(), payment, result.failureReason());
                    case UNKNOWN -> transactions.markUnknown(command.memberId(), payment, result.externalReferenceId());
                };
        return PaymentView.of(settled);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView getPayment(MemberId memberId, PaymentId paymentId) {
        Payment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
        payment.requireOwnedBy(memberId);
        return PaymentView.of(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView getPaymentByOrderId(MemberId memberId, String orderId) {
        // 회원으로 먼저 거릅니다. 주문번호는 클라이언트가 만드는 문자열이라 남의 것과 겹칠 수 있고,
        // 남의 결제는 "없는 것"이어야 합니다. 있는데 못 본다고 답하면 존재 여부가 샙니다.
        Payment payment = paymentRepository
                .findForOrder(memberId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
        return PaymentView.of(payment);
    }

    private static String canonicalHash(ApprovePaymentCommand command) {
        String canonical = String.join(
                "|",
                command.memberId().toString(),
                command.orderId(),
                command.walletId().toString(),
                command.merchantId().toString(),
                Long.toString(command.amount().amount()),
                command.amount().currency().name(),
                command.method().name());
        return RequestHasher.sha256(canonical);
    }
}
