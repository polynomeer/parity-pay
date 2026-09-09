package io.parity.pay.api.webhook;

import io.parity.pay.payment.application.service.CancellationRecoveryService;
import io.parity.pay.payment.application.service.PaymentRecoveryService;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.PaymentId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드 PG 웹훅 처리.
 *
 * <p><b>payload를 믿지 않습니다.</b> 웹훅이 "승인됐다"고 말해도 그것으로 돈을 움직이지 않고, 기관에
 * 직접 물어보는 복구 경로를 촉발할 뿐입니다. 이유는 두 가지입니다.
 *
 * <ul>
 *   <li>서명이 맞아도 payload가 사실이라는 보장은 없습니다. 기관이 잘못 보낼 수도 있습니다.
 *   <li>확정 경로가 둘이 되면 둘 다 맞는지 계속 확인해야 합니다. 복구와 웹훅이 같은 코드로 확정하면
 *       확인할 곳이 한 곳입니다.
 * </ul>
 *
 * <p>그래서 웹훅의 값어치는 <b>속도</b>입니다. 복구 작업은 유예 시간을 두고 도는데(기본 30초),
 * 웹훅이 오면 그 자리에서 확정됩니다.
 *
 * <p>중복과 역순은 그래도 막습니다. 중복은 불필요한 조회를, 역순은 오래된 사실을 근거로 한 조회를
 * 만들고, 무엇보다 나중에 누군가 payload를 믿도록 바꾸면 그때는 금액이 틀립니다.
 *
 * <p>근거: F-008, ADR-007, docs/09-consistency-recovery.md §11
 */
@Service
public class PgWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PgWebhookService.class);

    static final String PROVIDER = "MOCK_PG";

    private final JdbcTemplate jdbcTemplate;
    private final PaymentRecoveryService paymentRecovery;
    private final CancellationRecoveryService cancellationRecovery;
    private final Clock clock;

    PgWebhookService(
            JdbcTemplate jdbcTemplate,
            PaymentRecoveryService paymentRecovery,
            CancellationRecoveryService cancellationRecovery,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.paymentRecovery = paymentRecovery;
        this.cancellationRecovery = cancellationRecovery;
        this.clock = clock;
    }

    /**
     * 웹훅 하나를 처리합니다.
     *
     * <p>어떤 경우에도 예외를 던지지 않고 결과를 돌려줍니다. 기관에게는 200을 답해야 재전송이
     * 멈춥니다 — 우리가 이미 아는 사실이거나 우리 일이 아닌 것을 두고 기관이 계속 재시도하게 만들
     * 이유가 없습니다.
     */
    public Result handle(PgWebhookPayload payload) {
        if (!recordReceipt(payload)) {
            log.debug("duplicate webhook {} for {}", payload.eventId(), payload.externalKey());
            return Result.DUPLICATE;
        }
        if (!advanceCursor(payload)) {
            log.info(
                    "stale webhook {} for {} (sequence {})",
                    payload.eventId(),
                    payload.externalKey(),
                    payload.sequence());
            return Result.STALE;
        }
        return confirmByQuery(payload);
    }

    /**
     * 수신 이력을 남깁니다. 이미 있으면 중복입니다.
     *
     * <p>{@code INSERT ... ON CONFLICT DO NOTHING}의 반환 행 수로 판단합니다. 조회 후 삽입은 같은
     * 웹훅이 동시에 두 번 도착하는 경우를 막지 못합니다 — 기관의 재시도는 실제로 겹쳐서 옵니다.
     */
    @Transactional
    boolean recordReceipt(PgWebhookPayload payload) {
        return jdbcTemplate.update(
                        """
                        INSERT INTO webhook_receipt
                            (provider, event_id, external_key, event_type, sequence_no, received_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (provider, event_id) DO NOTHING
                        """,
                        PROVIDER,
                        payload.eventId(),
                        payload.externalKey(),
                        payload.eventType(),
                        payload.sequence(),
                        Timestamp.from(clock.instant()))
                == 1;
    }

    /**
     * 업무 키별 순번을 전진시킵니다. 뒤로 가는 웹훅은 여기서 걸립니다.
     *
     * <p>한 문장으로 처리합니다. 읽고 비교한 뒤 쓰면 두 웹훅이 동시에 도착했을 때 나중 것이 먼저
     * 것을 덮을 수 있습니다.
     */
    @Transactional
    boolean advanceCursor(PgWebhookPayload payload) {
        return jdbcTemplate.update(
                        """
                        INSERT INTO webhook_cursor (provider, external_key, sequence_no, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (provider, external_key) DO UPDATE
                           SET sequence_no = EXCLUDED.sequence_no,
                               updated_at = EXCLUDED.updated_at
                         WHERE webhook_cursor.sequence_no < EXCLUDED.sequence_no
                        """,
                        PROVIDER,
                        payload.externalKey(),
                        payload.sequence(),
                        Timestamp.from(clock.instant()))
                == 1;
    }

    /**
     * 기관에 직접 물어 확정합니다.
     *
     * <p>웹훅이 알려준 결과를 쓰지 않고 복구 작업과 같은 경로를 부릅니다. 이미 확정된 건이면 그
     * 경로가 스스로 아무것도 하지 않습니다.
     */
    private Result confirmByQuery(PgWebhookPayload payload) {
        UUID key;
        try {
            key = UUID.fromString(payload.externalKey());
        } catch (IllegalArgumentException e) {
            // 우리 업무 키가 아닙니다. 기관에게는 200을 답해 재전송을 멈춥니다.
            log.warn("webhook for an unrecognised key {}", payload.externalKey());
            return Result.IGNORED;
        }
        try {
            if (payload.isRefund()) {
                cancellationRecovery.resolveNow(CancellationId.of(key));
            } else {
                paymentRecovery.resolveNow(PaymentId.of(key));
            }
            return Result.ACCEPTED;
        } catch (RuntimeException e) {
            // 우리가 모르는 건이거나 조회가 실패했습니다. 어느 쪽이든 복구 작업이 다시 봅니다.
            log.warn("webhook {} could not be confirmed now: {}", payload.eventId(), e.toString());
            return Result.IGNORED;
        }
    }

    /** 처리 결과입니다. 기관에게는 어느 경우에도 200을 답합니다. */
    public enum Result {
        ACCEPTED,
        DUPLICATE,
        STALE,
        IGNORED
    }
}
