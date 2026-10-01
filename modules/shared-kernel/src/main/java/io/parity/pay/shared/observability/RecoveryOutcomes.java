package io.parity.pay.shared.observability;

/**
 * 복구가 한 건을 끝낸 순간을 기록합니다.
 *
 * <p>복구 행(`*_recovery`)은 확정과 함께 지워집니다. 그래서 확정된 뒤에는 "몇 번 만에 됐는가",
 * "조회가 답해 줘서인가 연속 '없음'이라서인가"를 어디에서도 볼 수 없고, 로그에만 남았습니다
 * (reports/13 발견 3). 그 순간에 여기로 한 줄 넘깁니다.
 *
 * <p>무엇을 묻기 위한 값인지가 정해져 있습니다. <b>연속 "없음"으로 확정한 건수</b>는 모델 검사가
 * 찾은 경로(M-030)가 실제로 얼마나 자주 지나가는지이고, 그 길을 사람에게 넘기기로 하면 그만큼이
 * 운영자 대기열에 쌓입니다. 고르기 전에 알아야 하는 값입니다(docs/09 §7).
 *
 * <p>업무 모듈은 이 값이 어디에 적히는지(지표인지 로그인지) 알지 못합니다. {@link
 * io.parity.pay.shared.security.RecentAuthentication}과 같은 자리입니다.
 *
 * <p>근거: docs/09-consistency-recovery.md §7·§8, reports/13 §5 발견 3, reports/11 M-030
 */
public interface RecoveryOutcomes {

    /** 아무것도 적지 않습니다. 복구 규칙을 지표 없이 시험할 때 씁니다. */
    RecoveryOutcomes NOOP = settled -> {};

    void settled(Settled settled);

    /**
     * 복구가 끝낸 한 건.
     *
     * @param target 어떤 복구인가
     * @param resolution 무엇이 이 건을 끝냈는가
     * @param outcome 업무 상태 이름 (`SUCCEEDED`·`FAILED`·`APPROVED`·`COMPLETED`·`PAID`). 사람에게
     *     넘긴 건은 아직 업무 상태가 바뀌지 않았으므로 {@code UNKNOWN}입니다
     * @param attemptCount 조회를 시도한 횟수
     * @param notFoundCount 외부에 기록이 "없음"으로 연속 확인된 횟수
     */
    record Settled(Target target, Resolution resolution, String outcome, int attemptCount, int notFoundCount) {}

    enum Target {
        TOP_UP,
        PAYMENT,
        CANCELLATION,
        PAYOUT
    }

    enum Resolution {
        /** 외부 조회가 승인·거절을 알려줬습니다. 확정의 근거가 외부 기록입니다. */
        QUERY,
        /**
         * 외부에 기록이 없다는 것이 연속으로 확인되어 실패로 확정했습니다. 근거는 "없다"이고, 그
         * 사이에 외부가 기록하면 틀립니다(M-030).
         */
        NOT_FOUND,
        /** 자동으로 확정하지 못해 사람에게 넘겼습니다. 업무 상태는 그대로입니다. */
        MANUAL_REVIEW
    }
}
