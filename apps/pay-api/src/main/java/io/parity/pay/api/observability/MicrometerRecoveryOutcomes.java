package io.parity.pay.api.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.shared.observability.RecoveryOutcomes;
import org.springframework.stereotype.Component;

/**
 * 복구가 끝낸 건을 지표로 적습니다.
 *
 * <p>복구 행은 확정과 함께 지워지므로, 끝난 뒤에 "몇 번 만에 됐는가"와 "무엇을 근거로 끝났는가"를
 * 볼 곳이 없었습니다(reports/13 발견 3). 세 가지를 내보냅니다.
 *
 * <ul>
 *   <li>{@code paritypay.recovery.settled} — 대상·확정 근거·업무 상태별 건수
 *   <li>{@code paritypay.recovery.attempts} — 확정까지의 조회 횟수 분포
 *   <li>{@code paritypay.recovery.not_found_confirmations} — 연속 "없음" 횟수 분포 (그 경로만)
 * </ul>
 *
 * <p>태그 조합은 대상 4 × 근거 3 × 상태 소수라 카디널리티가 늘지 않습니다. 식별자(결제 ID·회원 ID)는
 * 태그로 넣지 않습니다 — 지표가 사실상 로그가 되고, 그 로그는 지워지지도 않습니다.
 *
 * <p><b>이 값으로 답할 질문:</b> {@code resolution="NOT_FOUND"}의 건수는 모델 검사가 찾은 경로(M-030)가
 * 실제로 얼마나 지나가는지이고, 그 경로를 사람에게 넘기기로 하면 그만큼이 운영자 대기열로 갑니다.
 * 고르기 전에 알아야 하는 값입니다(docs/09 §7).
 *
 * <p>근거: docs/09-consistency-recovery.md §7·§8, docs/05-technical-design.md §12
 */
@Component
class MicrometerRecoveryOutcomes implements RecoveryOutcomes {

    private final MeterRegistry registry;

    MicrometerRecoveryOutcomes(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void settled(Settled settled) {
        String target = settled.target().name();
        String resolution = settled.resolution().name();

        registry.counter(
                        "paritypay.recovery.settled",
                        "target",
                        target,
                        "resolution",
                        resolution,
                        "outcome",
                        settled.outcome())
                .increment();

        DistributionSummary.builder("paritypay.recovery.attempts")
                .description("복구가 확정하기까지 외부 상태를 조회한 횟수")
                .tag("target", target)
                .tag("resolution", resolution)
                .register(registry)
                .record(settled.attemptCount());

        if (settled.notFoundCount() > 0) {
            DistributionSummary.builder("paritypay.recovery.not_found_confirmations")
                    .description("외부에 기록이 \"없음\"으로 연속 확인된 횟수")
                    .tag("target", target)
                    .tag("resolution", resolution)
                    .register(registry)
                    .record(settled.notFoundCount());
        }
    }
}
