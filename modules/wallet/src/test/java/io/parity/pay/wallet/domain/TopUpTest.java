package io.parity.pay.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TopUpTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.of("top-up-key-0001");

    @Test
    @DisplayName("성공 전이는 완료 금액을 요청 금액으로 채운다")
    void succeedSetsCompletedAmount() {
        TopUp succeeded = newTopUp().begin().succeed("ext-1", NOW);

        assertThat(succeeded.status()).isEqualTo(TopUpStatus.SUCCEEDED);
        assertThat(succeeded.completedAmount()).isEqualTo(Money.krw(100_000));
        assertThat(succeeded.completedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("ADR-007: 타임아웃은 실패가 아니라 UNKNOWN이며 금액을 확정하지 않는다")
    void unknownDoesNotSettleAmount() {
        TopUp unknown = newTopUp().begin().markUnknown("ext-1");

        assertThat(unknown.status()).isEqualTo(TopUpStatus.UNKNOWN);
        assertThat(unknown.completedAmount()).isEqualTo(Money.krw(0));
        assertThat(unknown.status().isFinal()).isFalse();
    }

    @Test
    @DisplayName("UNKNOWN은 조회 결과에 따라 성공·실패로 수렴한다")
    void unknownConvergesToFinalState() {
        TopUp unknown = newTopUp().begin().markUnknown("ext-1");

        assertThat(unknown.succeed("ext-1", NOW).status()).isEqualTo(TopUpStatus.SUCCEEDED);
        assertThat(unknown.fail("DECLINED", NOW).status()).isEqualTo(TopUpStatus.FAILED);
    }

    @Test
    @DisplayName("최종 상태에서는 더 이상 전이할 수 없다")
    void finalStatesRejectFurtherTransitions() {
        TopUp succeeded = newTopUp().begin().succeed("ext-1", NOW);

        assertThatThrownBy(() -> succeeded.fail("late failure", NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("REQUESTED에서 바로 성공으로 건너뛸 수 없다")
    void rejectsSkippingProcessing() {
        assertThatThrownBy(() -> newTopUp().succeed("ext-1", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("REQUESTED to SUCCEEDED");
    }

    @Test
    @DisplayName("0원 이하 충전은 만들 수 없다")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> TopUp.request(
                        WalletId.generate(), BankAccountId.generate(), Money.krw(0), KEY, NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.INVALID_AMOUNT));
    }

    private static TopUp newTopUp() {
        return TopUp.request(
                WalletId.generate(), BankAccountId.generate(), Money.krw(100_000), KEY, NOW);
    }
}
