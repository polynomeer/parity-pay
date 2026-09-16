package io.parity.pay.payment.application.port.out;

/**
 * 외부 PG에 요청을 **보내지 않고** 거절했을 때.
 *
 * <p>타임아웃({@code UNKNOWN})과 다릅니다. 타임아웃은 요청이 나갔고 결과를 모르는 것이라 복구가 조회로
 * 확정해야 하지만, 이것은 차단기가 열려 있거나 동시 호출 상한·기관 TPS 상한에 걸려 요청 자체가 나가지
 * 않은 것입니다. 외부에 아무 일도 일어나지 않았으므로 **알려진 실패**로 확정할 수 있고 조회할 것도
 * 없습니다. 이 구분이 없으면 차단기가 열린 동안의 모든 결제가 미확정으로 쌓여 복구 작업이 존재하지
 * 않는 기록을 세 번씩 물어보게 됩니다.
 *
 * <p>근거: ADR-014, docs/09-consistency-recovery.md §7
 */
public class PgCallRejectedException extends RuntimeException {

    private final String reason;

    public PgCallRejectedException(String reason) {
        super("pg call rejected before sending: " + reason);
        this.reason = reason;
    }

    /** {@code CIRCUIT_OPEN}·{@code BULKHEAD_FULL}·{@code RATE_LIMITED} 중 하나입니다. 결제의 실패 사유로 남습니다. */
    public String reason() {
        return reason;
    }
}
