package io.parity.pay.api.operations;

import io.parity.pay.api.mockbank.BankUnknownResultException;
import io.parity.pay.api.mockbank.MockBankClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통합 거래 타임라인.
 *
 * <p>하나의 참조 ID로 업무·원장·외부기관·이벤트·운영 작업·대사 기록을 시간순으로 모읍니다. 운영자가
 * 여러 화면을 오가며 사실을 재구성하지 않아도 되게 하는 것이 목적입니다.
 * 근거: FR-012, DoD-07, docs/01-product-plan.md §7
 *
 * <p>여러 모듈의 테이블을 함께 읽는 조회 전용 서비스이며, 조립 지점에 둡니다. 업무 모듈은 서로의
 * 테이블을 알지 못합니다. 근거: docs/05-technical-design.md §5
 */
@Service
public class TransactionTimelineService {

    private static final Logger log = LoggerFactory.getLogger(TransactionTimelineService.class);

    private final JdbcTemplate jdbcTemplate;
    private final MockBankClient bankClient;

    TransactionTimelineService(JdbcTemplate jdbcTemplate, MockBankClient bankClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.bankClient = bankClient;
    }

    /**
     * 참조 ID로 타임라인을 만듭니다.
     *
     * <p>참조 ID는 결제 ID, 주문 ID, 충전 ID, 취소 ID, 정산 ID 중 무엇이든 될 수 있습니다. 운영자가
     * 고객에게 받은 식별자가 어떤 종류인지 미리 알 수 없기 때문입니다.
     */
    @Transactional(readOnly = true)
    public Timeline of(String referenceId) {
        List<TimelineEntry> entries = new ArrayList<>();

        entries.addAll(query(
                """
                SELECT 'TOP_UP' AS kind, t.top_up_id::text AS id, t.status,
                       t.requested_amount AS amount, t.currency,
                       coalesce(t.completed_at, t.requested_at) AS occurred_at,
                       'wallet=' || t.wallet_id AS detail
                  FROM top_up t
                 WHERE t.top_up_id::text = ?
                """,
                referenceId));

        entries.addAll(query(
                """
                SELECT 'PAYMENT' AS kind, p.payment_id::text AS id, p.status,
                       p.approved_amount AS amount, p.currency,
                       coalesce(p.approved_at, p.created_at) AS occurred_at,
                       'order=' || p.order_id || ' merchant=' || p.merchant_id AS detail
                  FROM payment p
                 WHERE p.payment_id::text = ? OR p.order_id = ?
                """,
                referenceId,
                referenceId));

        entries.addAll(query(
                """
                SELECT 'PAYMENT_CANCELLATION' AS kind, c.cancellation_id::text AS id, c.status,
                       c.completed_amount AS amount, c.currency,
                       coalesce(c.completed_at, c.requested_at) AS occurred_at,
                       'payment=' || c.payment_id AS detail
                  FROM payment_cancellation c
                  JOIN payment p ON p.payment_id = c.payment_id
                 WHERE c.cancellation_id::text = ? OR c.payment_id::text = ? OR p.order_id = ?
                """,
                referenceId,
                referenceId,
                referenceId));

        entries.addAll(query(
                """
                SELECT 'LEDGER' AS kind, lt.transaction_id::text AS id, lt.status,
                       -- PostgreSQL의 sum(bigint)은 numeric을 돌려주므로 bigint로 되돌립니다.
                       (SELECT coalesce(sum(e.amount), 0)::bigint FROM ledger_entry e
                         WHERE e.transaction_id = lt.transaction_id AND e.direction = 'DEBIT') AS amount,
                       lt.currency, lt.effective_at AS occurred_at,
                       lt.transaction_type || ' ref=' || lt.reference_id AS detail
                  FROM ledger_transaction lt
                 WHERE lt.reference_id::text = ?
                    OR lt.reference_id IN (SELECT c.cancellation_id FROM payment_cancellation c
                                            WHERE c.payment_id::text = ?)
                """,
                referenceId,
                referenceId));

        entries.addAll(institutionEntries(referenceId));

        entries.addAll(query(
                """
                SELECT 'EVENT' AS kind, o.event_id::text AS id, o.status,
                       NULL::bigint AS amount, NULL AS currency, o.occurred_at,
                       o.event_type AS detail
                  FROM outbox_event o
                 WHERE o.aggregate_id = ?
                    OR o.payload::text LIKE '%' || ? || '%'
                """,
                referenceId, referenceId));

        entries.addAll(query(
                """
                SELECT 'OPERATION' AS kind, a.audit_id::text AS id, a.result AS status,
                       NULL::bigint AS amount, NULL AS currency, a.created_at AS occurred_at,
                       a.actor || ' ' || a.action || ' reason=' || coalesce(a.reason, '') AS detail
                  FROM audit_log a
                 WHERE a.resource_id = ?
                """,
                referenceId));

        entries.addAll(query(
                """
                SELECT 'RECONCILIATION' AS kind, m.mismatch_id::text AS id, m.resolution_status AS status,
                       coalesce(m.internal_amount, m.external_amount) AS amount, m.currency,
                       m.detected_at AS occurred_at,
                       m.mismatch_type || ' ' || coalesce(m.detail, '') AS detail
                  FROM reconciliation_mismatch m
                 WHERE m.reference_id = ?
                """,
                referenceId));

        entries.sort(Comparator.comparing(TimelineEntry::occurredAt));
        return new Timeline(referenceId, entries);
    }

    /**
     * 기관이 알고 있는 사실입니다.
     *
     * <p>예전에는 기관의 표를 같은 데이터베이스에서 읽었습니다. 지금은 기관에 물어봅니다. 없으면
     * 아무 줄도 만들지 않지만, <b>물어보지 못한 경우는 그렇게 두지 않습니다</b> — 조용히 빼면
     * 운영자가 "기관에 기록이 없다"로 읽습니다. 대사에서 배운 것과 같은 구분입니다(F-011).
     *
     * <p>기관이 답하지 않는다고 타임라인 전체를 실패시키지도 않습니다. 우리 쪽 사실은 그대로 보여
     * 주고, 기관 부분만 "물어보지 못함"으로 표시합니다.
     */
    private List<TimelineEntry> institutionEntries(String referenceId) {
        List<TimelineEntry> entries = new ArrayList<>();
        addInstitutionEntry(
                entries,
                referenceId,
                "EXTERNAL_WITHDRAWAL",
                "mock-bank withdrawal",
                () -> bankClient.withdrawalRecord(referenceId));
        addInstitutionEntry(
                entries,
                referenceId,
                "EXTERNAL_PAYOUT",
                "mock-bank payout",
                () -> bankClient.payoutRecord(referenceId));
        return entries;
    }

    private void addInstitutionEntry(
            List<TimelineEntry> entries,
            String referenceId,
            String kind,
            String detail,
            java.util.function.Supplier<java.util.Optional<MockBankClient.StatementLine>> fetch) {
        try {
            fetch.get()
                    .ifPresent(line -> entries.add(new TimelineEntry(
                            kind,
                            line.externalKey(),
                            line.status(),
                            line.amount(),
                            line.currency(),
                            detail,
                            line.occurredAt())));
        } catch (BankUnknownResultException e) {
            log.warn("could not ask the bank about {} for the timeline", referenceId);
            entries.add(new TimelineEntry(
                    kind,
                    referenceId,
                    "UNAVAILABLE",
                    null,
                    null,
                    detail + " (기관에 물어보지 못했습니다 — 기록이 없다는 뜻이 아닙니다)",
                    Instant.now()));
        }
    }

    private List<TimelineEntry> query(String sql, Object... args) {
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    Timestamp occurredAt = rs.getTimestamp("occurred_at");
                    return new TimelineEntry(
                            rs.getString("kind"),
                            rs.getString("id"),
                            rs.getString("status"),
                            rs.getObject("amount", Long.class),
                            rs.getString("currency"),
                            rs.getString("detail"),
                            occurredAt == null ? Instant.EPOCH : occurredAt.toInstant());
                },
                args);
    }

    public record Timeline(String referenceId, List<TimelineEntry> entries) {

        /** 운영자가 가장 먼저 보는 요약입니다. */
        public Map<String, Long> countsByKind() {
            return entries.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            TimelineEntry::kind, java.util.stream.Collectors.counting()));
        }
    }

    public record TimelineEntry(
            String kind, String id, String status, Long amount, String currency, String detail, Instant occurredAt) {}
}
