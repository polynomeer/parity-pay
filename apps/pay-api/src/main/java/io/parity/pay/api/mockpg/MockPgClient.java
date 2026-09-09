package io.parity.pay.api.mockpg;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 외부 PG HTTP 클라이언트.
 *
 * <p>은행 클라이언트와 같은 규칙입니다. 응답을 받지 못한 모든 경우를 예외 하나로 모으고, 호출자가
 * 그것을 실패가 아니라 미확정으로 다룹니다. 조회만 예외입니다 — 조회 실패는 "모른다"를 그대로
 * 돌려줘야 하며, "기록 없음"과 같은 값이 되면 조회 장애가 곧 "돈이 안 움직였다"는 결론이 됩니다.
 *
 * <p>근거: ADR-007, docs/05-technical-design.md §10
 */
@Component
public class MockPgClient {

    private static final Logger log = LoggerFactory.getLogger(MockPgClient.class);

    private final RestClient restClient;

    MockPgClient(MockPgProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public ResultResponse approve(String externalKey, UUID merchantId, String orderId, long amount) {
        return call(
                () -> restClient
                        .post()
                        .uri("/mock-pg/approvals")
                        .body(new ApprovalRequest(externalKey, merchantId.toString(), orderId, amount))
                        .retrieve()
                        .body(ResultResponse.class),
                "approval " + externalKey);
    }

    public ResultResponse refund(String externalKey, String paymentKey, long amount) {
        return call(
                () -> restClient
                        .post()
                        .uri("/mock-pg/refunds")
                        .body(new RefundRequest(externalKey, paymentKey, amount))
                        .retrieve()
                        .body(ResultResponse.class),
                "refund " + externalKey);
    }

    public Optional<String> approvalStatus(String externalKey) {
        return status("/mock-pg/approvals/" + externalKey);
    }

    public Optional<String> refundStatus(String externalKey) {
        return status("/mock-pg/refunds/" + externalKey);
    }

    /** 기관의 장부와 장애 모드를 함께 초기화합니다. */
    public void resetInstitution() {
        restClient.post().uri("/mock-pg/admin/reset").retrieve().toBodilessEntity();
    }

    /** 기관에 남은 건수입니다. {@code table}은 approvals 또는 refunds입니다. */
    public long count(String table, String status) {
        Long value = restClient
                .get()
                .uri(builder -> {
                    var uri = builder.path("/mock-pg/admin/{table}/count");
                    if (status != null) {
                        uri = uri.queryParam("status", status);
                    }
                    return uri.build(table);
                })
                .retrieve()
                .body(Long.class);
        return value == null ? 0L : value;
    }

    public void setBehavior(BehaviorRequest request) {
        restClient
                .post()
                .uri("/mock-pg/admin/behavior")
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private Optional<String> status(String uri) {
        StatusResponse response =
                call(() -> restClient.get().uri(uri).retrieve().body(StatusResponse.class), "status query " + uri);
        if (response == null || "NOT_FOUND".equals(response.status())) {
            return Optional.empty();
        }
        return Optional.of(response.status());
    }

    private <T> T call(Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (RestClientException e) {
            log.warn("mock pg call did not return a result: {}", what);
            throw new PgUnknownResultException("mock pg did not answer: " + what, e);
        }
    }

    record ApprovalRequest(String externalKey, String merchantId, String orderId, long amount) {}

    record RefundRequest(String externalKey, String paymentKey, long amount) {}

    public record ResultResponse(boolean succeeded, String externalReferenceId, String failureReason) {}

    record StatusResponse(String status) {}

    public record BehaviorRequest(
            String webhookMode,
            String webhookUrl,
            String approvalMode,
            String refundMode,
            Boolean approvalStatusQueryAvailable,
            Boolean refundStatusQueryAvailable,
            Long hangForMillis,
            Boolean reset) {}
}
