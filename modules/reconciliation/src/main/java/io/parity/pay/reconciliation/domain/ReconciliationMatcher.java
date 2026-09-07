package io.parity.pay.reconciliation.domain;

import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 내부·외부 기록 비교 도메인 서비스.
 *
 * <p>I/O를 수행하지 않습니다. 기록 목록을 받아 차이 목록을 돌려줍니다. 이 규칙이 대사의 전부이므로
 * 단위 테스트로 전수 검증할 수 있어야 합니다. 근거: docs/06-domain-state-design.md §8
 *
 * <p>지연 허용 시간 안에 있는 차이는 아직 차이가 아닙니다. 외부 기록이 우리보다 늦게 도착하는 것은
 * 정상이며, 이를 즉시 불일치로 올리면 운영자가 노이즈에 묻힙니다.
 * 근거: docs/04-payment-policy.md §9, docs/09-consistency-recovery.md §10
 */
public final class ReconciliationMatcher {

    private ReconciliationMatcher() {}

    public static List<ReconciliationMismatch> match(
            UUID runId,
            List<InternalRecord> internalRecords,
            List<ExternalRecord> externalRecords,
            Duration delayTolerance,
            Instant now) {

        List<ReconciliationMismatch> mismatches = new ArrayList<>();
        Map<String, ExternalRecord> externalByKey = new HashMap<>();
        for (ExternalRecord external : externalRecords) {
            externalByKey.put(external.externalReferenceId(), external);
        }

        Set<String> matchedExternalKeys = new HashSet<>();
        Map<String, String> externalKeyToInternalId = new HashMap<>();

        for (InternalRecord internal : internalRecords) {
            String externalKey = internal.externalReferenceId();

            // 같은 외부 참조를 두 내부 기록이 가리키면 중복입니다.
            if (externalKey != null) {
                String previous = externalKeyToInternalId.put(externalKey, internal.referenceId());
                if (previous != null) {
                    mismatches.add(detected(
                            runId,
                            MismatchType.DUPLICATE,
                            internal,
                            externalKey,
                            internal.amount(),
                            null,
                            "internal records " + previous + " and " + internal.referenceId()
                                    + " share the same external reference",
                            now));
                    continue;
                }
            }

            ExternalRecord external = externalKey == null ? null : externalByKey.get(externalKey);
            if (external != null) {
                matchedExternalKeys.add(externalKey);
            }

            boolean withinTolerance = isWithinTolerance(internal.occurredAt(), delayTolerance, now);

            if (external == null) {
                if (internal.outcome() == Outcome.SUCCEEDED && !withinTolerance) {
                    // 우리는 성공으로 알고 돈을 움직였는데 외부에 근거가 없습니다.
                    mismatches.add(detected(
                            runId,
                            MismatchType.INTERNAL_ONLY,
                            internal,
                            externalKey,
                            internal.amount(),
                            null,
                            "internal record is settled but no external record exists",
                            now));
                } else if (internal.outcome() == Outcome.PENDING && !withinTolerance) {
                    mismatches.add(detected(
                            runId,
                            MismatchType.STATUS_MISMATCH,
                            internal,
                            externalKey,
                            internal.amount(),
                            null,
                            "internal record is still pending beyond the delay tolerance",
                            now));
                }
                addLedgerMismatchIfNeeded(runId, internal, mismatches, now);
                continue;
            }

            if (internal.amount() != external.amount()) {
                // 금액 차이는 자동으로 맞추지 않습니다. 원인을 모른 채 숫자를 맞추면 문제를 덮습니다.
                mismatches.add(detected(
                        runId,
                        MismatchType.AMOUNT_MISMATCH,
                        internal,
                        externalKey,
                        internal.amount(),
                        external.amount(),
                        "amount differs between internal and external records",
                        now));
            } else if (internal.outcome() != external.outcome()) {
                if (internal.outcome() == Outcome.PENDING && withinTolerance) {
                    // 아직 확정 중입니다. 다음 대사까지 기다립니다.
                    addLedgerMismatchIfNeeded(runId, internal, mismatches, now);
                    continue;
                }
                mismatches.add(detected(
                        runId,
                        MismatchType.STATUS_MISMATCH,
                        internal,
                        externalKey,
                        internal.amount(),
                        external.amount(),
                        "internal " + internal.outcome() + " vs external " + external.outcome(),
                        now));
            }

            addLedgerMismatchIfNeeded(runId, internal, mismatches, now);
        }

        for (ExternalRecord external : externalRecords) {
            if (matchedExternalKeys.contains(external.externalReferenceId())) {
                continue;
            }
            if (external.outcome() == Outcome.FAILED) {
                // 외부가 실패로 기록한 요청은 우리 쪽에 아무것도 없어도 문제가 아닙니다.
                continue;
            }
            if (isWithinTolerance(external.occurredAt(), delayTolerance, now)) {
                continue;
            }
            mismatches.add(new ReconciliationMismatchBuilder(runId, now).externalOnly(external));
        }

        return mismatches;
    }

    /**
     * 업무는 성공했는데 원장 거래가 없는 경우입니다.
     *
     * <p>원장이 진실이므로 이 차이는 외부와의 차이보다 심각합니다. 잔액이 근거 없이 움직였다는
     * 뜻이기 때문입니다. 근거: ADR-003, INV-004
     */
    private static void addLedgerMismatchIfNeeded(
            UUID runId, InternalRecord internal, List<ReconciliationMismatch> mismatches, Instant now) {
        if (internal.outcome() == Outcome.SUCCEEDED && !internal.hasLedgerTransaction()) {
            mismatches.add(detected(
                    runId,
                    MismatchType.LEDGER_MISSING,
                    internal,
                    internal.externalReferenceId(),
                    internal.amount(),
                    null,
                    "settled business record has no ledger transaction",
                    now));
        }
    }

    private static boolean isWithinTolerance(Instant occurredAt, Duration tolerance, Instant now) {
        return occurredAt != null && occurredAt.isAfter(now.minus(tolerance));
    }

    private static ReconciliationMismatch detected(
            UUID runId,
            MismatchType type,
            InternalRecord internal,
            String externalReferenceId,
            Long internalAmount,
            Long externalAmount,
            String detail,
            Instant now) {
        return ReconciliationMismatch.detected(
                runId,
                type,
                internal.referenceType(),
                internal.referenceId(),
                externalReferenceId,
                internalAmount,
                externalAmount,
                internal.currency(),
                detail,
                now);
    }

    /** 외부에만 존재하는 기록은 내부 참조가 없으므로 별도로 만듭니다. */
    private record ReconciliationMismatchBuilder(UUID runId, Instant now) {

        ReconciliationMismatch externalOnly(ExternalRecord external) {
            return ReconciliationMismatch.detected(
                    runId,
                    MismatchType.EXTERNAL_ONLY,
                    "EXTERNAL",
                    external.externalReferenceId(),
                    external.externalReferenceId(),
                    null,
                    external.amount(),
                    external.currency(),
                    "external record has no matching internal record",
                    now);
        }
    }
}
