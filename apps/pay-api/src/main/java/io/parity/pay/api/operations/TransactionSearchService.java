package io.parity.pay.api.operations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 식별자 해석.
 *
 * <p>운영자는 고객이 들고 온 값이 무엇인지 모른 채 검색창에 넣습니다. 이 서비스는 그 값이 무엇인지
 * 알아내고, 타임라인을 열 수 있는 <b>참조 ID 목록</b>을 돌려줍니다.
 *
 * <p><b>타임라인과 분리한 이유가 있습니다.</b> 결제 ID·주문 ID는 거래 하나를 가리키지만 지갑 ID와
 * 회원 ID는 거래 <b>여럿</b>을 가리킵니다. 지갑 하나에 거래가 수천 건일 수 있으므로 "하나의
 * 타임라인"으로 합칠 수 없습니다. 그래서 해석은 후보를 돌려주고, 타임라인은 그중 하나를 받습니다.
 *
 * <p>이벤트 ID는 이 단계에서 Aggregate ID로 옮겨집니다. 그러면 타임라인 쿼리는 바뀌지 않아도
 * 됩니다.
 *
 * <p>멱등 키와 Trace ID는 일부러 받지 않습니다. 멱등 키는 개인정보에 준해 다루는 값이고, Trace ID
 * 검색은 트레이스 백엔드가 이미 하는 일입니다. 근거: docs/16-ui-implementation-plan.md §2.3
 */
@Service
public class TransactionSearchService {

    /** 지갑·회원처럼 여럿을 가리키는 식별자에서 한 번에 돌려주는 최대 건수입니다. */
    private static final int MAX_REFERENCES = 20;

    private final JdbcTemplate jdbcTemplate;

    TransactionSearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public SearchResult resolve(String query) {
        String trimmed = query.trim();
        UUID uuid = parseUuid(trimmed);
        if (uuid == null) {
            // UUID가 아니면 주문 ID입니다. 주문 ID는 클라이언트가 만드는 문자열입니다.
            return orderReference(trimmed);
        }

        for (DirectMatch match : DIRECT_MATCHES) {
            if (exists(match.sql(), uuid)) {
                return new SearchResult(trimmed, match.kind(), List.of(new Reference(trimmed, match.kind(), null)));
            }
        }

        // 이벤트 ID는 Aggregate로 옮깁니다. 타임라인은 업무 참조로 도는 것이 자연스럽습니다.
        List<String> aggregates = jdbcTemplate.queryForList(
                "SELECT aggregate_id::text FROM outbox_event WHERE event_id = ?", String.class, uuid);
        if (!aggregates.isEmpty()) {
            return new SearchResult(
                    trimmed, "EVENT", List.of(new Reference(aggregates.get(0), "AGGREGATE", "이벤트가 가리키는 업무 거래")));
        }

        // 원장 거래 ID는 자기가 기록한 업무 참조로 옮깁니다.
        List<String> ledgerReferences = jdbcTemplate.queryForList(
                "SELECT reference_id::text FROM ledger_transaction WHERE transaction_id = ?", String.class, uuid);
        if (!ledgerReferences.isEmpty()) {
            return new SearchResult(
                    trimmed,
                    "LEDGER_TRANSACTION",
                    List.of(new Reference(ledgerReferences.get(0), "REFERENCE", "원장 거래가 가리키는 업무 거래")));
        }

        if (exists("SELECT 1 FROM wallet WHERE wallet_id = ?", uuid)) {
            return new SearchResult(trimmed, "WALLET", referencesForWallet(uuid));
        }
        if (exists("SELECT 1 FROM member WHERE member_id = ?", uuid)) {
            return new SearchResult(trimmed, "MEMBER", referencesForMember(uuid));
        }
        return new SearchResult(trimmed, "UNKNOWN", List.of());
    }

    /** 이 값 자체가 타임라인을 여는 참조인 경우들입니다. */
    private static final List<DirectMatch> DIRECT_MATCHES = List.of(
            new DirectMatch("PAYMENT", "SELECT 1 FROM payment WHERE payment_id = ?"),
            new DirectMatch("TOP_UP", "SELECT 1 FROM top_up WHERE top_up_id = ?"),
            new DirectMatch("CANCELLATION", "SELECT 1 FROM payment_cancellation WHERE cancellation_id = ?"),
            new DirectMatch("SETTLEMENT", "SELECT 1 FROM settlement WHERE settlement_id = ?"));

    private SearchResult orderReference(String orderId) {
        List<String> payments = jdbcTemplate.queryForList(
                "SELECT payment_id::text FROM payment WHERE order_id = ? ORDER BY created_at DESC",
                String.class,
                orderId);
        if (payments.isEmpty()) {
            return new SearchResult(orderId, "UNKNOWN", List.of());
        }
        // 주문 ID는 타임라인이 직접 받으므로 그대로 넘깁니다.
        return new SearchResult(orderId, "ORDER", List.of(new Reference(orderId, "ORDER", null)));
    }

    private List<Reference> referencesForWallet(UUID walletId) {
        List<Reference> references = new ArrayList<>(jdbcTemplate.query(
                """
                SELECT payment_id::text, order_id, status, created_at
                  FROM payment WHERE wallet_id = ? ORDER BY created_at DESC LIMIT ?
                """,
                (rs, i) -> new Reference(rs.getString(1), "PAYMENT", "주문 " + rs.getString(2) + " · " + rs.getString(3)),
                walletId,
                MAX_REFERENCES));
        references.addAll(jdbcTemplate.query(
                """
                SELECT top_up_id::text, status, requested_at
                  FROM top_up WHERE wallet_id = ? ORDER BY requested_at DESC LIMIT ?
                """,
                (rs, i) -> new Reference(rs.getString(1), "TOP_UP", rs.getString(2)),
                walletId,
                MAX_REFERENCES));
        return references;
    }

    private List<Reference> referencesForMember(UUID memberId) {
        List<String> wallets =
                jdbcTemplate
                        .queryForList("SELECT wallet_id FROM wallet WHERE member_id = ?", UUID.class, memberId)
                        .stream()
                        .map(UUID::toString)
                        .toList();
        List<Reference> references = new ArrayList<>();
        for (String wallet : wallets) {
            references.add(new Reference(wallet, "WALLET", "이 회원의 지갑"));
            references.addAll(referencesForWallet(UUID.fromString(wallet)));
        }
        return references;
    }

    private boolean exists(String sql, UUID id) {
        return !jdbcTemplate.queryForList(sql, Integer.class, id).isEmpty();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record DirectMatch(String kind, String sql) {}

    /**
     * 해석 결과입니다.
     *
     * @param kind 입력값이 무엇이었는지
     * @param references 타임라인을 열 수 있는 참조들. 비어 있으면 못 찾은 것입니다
     */
    public record SearchResult(String query, String kind, List<Reference> references) {}

    public record Reference(String referenceId, String kind, String summary) {}
}
