package io.parity.pay.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 6종 불일치 분류를 전수 검증합니다. 근거: docs/09-consistency-recovery.md §10 */
class ReconciliationMatcherTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(2));
    private static final Duration TOLERANCE = Duration.ofMinutes(30);

    @Test
    @DisplayName("양쪽이 같으면 차이가 없다")
    void matchingRecordsProduceNoMismatch() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, true, OLD)),
                List.of(external("ext-1", Outcome.SUCCEEDED, 100_000, OLD)),
                TOLERANCE,
                NOW);

        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("INTERNAL_ONLY: 우리는 성공인데 외부에 기록이 없다")
    void internalOnly() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, true, OLD)),
                List.of(),
                TOLERANCE,
                NOW);

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.INTERNAL_ONLY);
            assertThat(mismatch.amountDifference()).isEqualTo(100_000);
            assertThat(mismatch.type().autoResolvable()).isFalse();
        });
    }

    @Test
    @DisplayName("EXTERNAL_ONLY: 외부는 처리했는데 우리에게 기록이 없다")
    void externalOnly() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(),
                List.of(external("ext-9", Outcome.SUCCEEDED, 50_000, OLD)),
                TOLERANCE,
                NOW);

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.EXTERNAL_ONLY);
            assertThat(mismatch.externalAmount()).isEqualTo(50_000);
            assertThat(mismatch.internalAmount()).isNull();
        });
    }

    @Test
    @DisplayName("외부가 실패로 기록한 요청은 우리에게 없어도 차이가 아니다")
    void failedExternalWithoutInternalIsNotAMismatch() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID, List.of(), List.of(external("ext-9", Outcome.FAILED, 50_000, OLD)), TOLERANCE, NOW);

        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("STATUS_MISMATCH: 양쪽에 있지만 결과가 다르다")
    void statusMismatch() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, true, OLD)),
                List.of(external("ext-1", Outcome.FAILED, 100_000, OLD)),
                TOLERANCE,
                NOW);

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.STATUS_MISMATCH);
            assertThat(mismatch.detail()).contains("SUCCEEDED").contains("FAILED");
        });
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH: 금액이 다르면 상태와 무관하게 금액 차이로 올린다")
    void amountMismatch() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, true, OLD)),
                List.of(external("ext-1", Outcome.SUCCEEDED, 90_000, OLD)),
                TOLERANCE,
                NOW);

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(mismatch.amountDifference()).isEqualTo(10_000);
            // 금액 차이는 절대 자동 보정 대상이 아닙니다.
            assertThat(mismatch.type().autoResolvable()).isFalse();
        });
    }

    @Test
    @DisplayName("DUPLICATE: 두 내부 기록이 같은 외부 참조를 가리킨다")
    void duplicate() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(
                        internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, true, OLD),
                        internal("t-2", "ext-1", Outcome.SUCCEEDED, 100_000, true, OLD)),
                List.of(external("ext-1", Outcome.SUCCEEDED, 100_000, OLD)),
                TOLERANCE,
                NOW);

        assertThat(mismatches)
                .extracting(ReconciliationMismatch::type)
                .containsExactly(MismatchType.DUPLICATE);
    }

    @Test
    @DisplayName("LEDGER_MISSING: 업무는 성공인데 원장 거래가 없다")
    void ledgerMissing() {
        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, false, OLD)),
                List.of(external("ext-1", Outcome.SUCCEEDED, 100_000, OLD)),
                TOLERANCE,
                NOW);

        assertThat(mismatches)
                .extracting(ReconciliationMismatch::type)
                .containsExactly(MismatchType.LEDGER_MISSING);
    }

    @Test
    @DisplayName("지연 허용 시간 안의 차이는 아직 차이가 아니다")
    void recentDifferencesAreToleratedUntilTheNextRun() {
        Instant recent = NOW.minus(Duration.ofMinutes(5));

        List<ReconciliationMismatch> mismatches = ReconciliationMatcher.match(
                RUN_ID,
                List.of(internal("t-1", "ext-1", Outcome.SUCCEEDED, 100_000, true, recent)),
                List.of(),
                TOLERANCE,
                NOW);

        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("확정 중인 거래는 허용 시간이 지나야 차이로 올린다")
    void pendingBecomesMismatchOnlyAfterTolerance() {
        assertThat(ReconciliationMatcher.match(
                        RUN_ID,
                        List.of(internal(
                                "t-1", "ext-1", Outcome.PENDING, 100_000, false, NOW.minusSeconds(60))),
                        List.of(),
                        TOLERANCE,
                        NOW))
                .isEmpty();

        assertThat(ReconciliationMatcher.match(
                        RUN_ID,
                        List.of(internal("t-1", "ext-1", Outcome.PENDING, 100_000, false, OLD)),
                        List.of(),
                        TOLERANCE,
                        NOW))
                .extracting(ReconciliationMismatch::type)
                .containsExactly(MismatchType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("외부가 성공인데 우리가 아직 확정 중이면 허용 시간 안에서는 기다린다")
    void pendingInternalWithSucceededExternalWaits() {
        assertThat(ReconciliationMatcher.match(
                        RUN_ID,
                        List.of(internal(
                                "t-1", "ext-1", Outcome.PENDING, 100_000, false, NOW.minusSeconds(60))),
                        List.of(external("ext-1", Outcome.SUCCEEDED, 100_000, NOW.minusSeconds(60))),
                        TOLERANCE,
                        NOW))
                .isEmpty();
    }

    private static InternalRecord internal(
            String id,
            String externalKey,
            Outcome outcome,
            long amount,
            boolean hasLedger,
            Instant occurredAt) {
        return new InternalRecord(
                "TOP_UP", id, externalKey, outcome, amount, "KRW", hasLedger, occurredAt);
    }

    private static ExternalRecord external(
            String externalKey, Outcome outcome, long amount, Instant occurredAt) {
        return new ExternalRecord(externalKey, outcome, amount, "KRW", occurredAt);
    }
}
