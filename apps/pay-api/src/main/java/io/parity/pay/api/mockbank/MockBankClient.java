package io.parity.pay.api.mockbank;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 외부 기관 HTTP 클라이언트.
 *
 * <p>이전에는 같은 프로세스의 빈을 호출했습니다. 그때는 "타임아웃"이 우리가 던진 예외였고, 실제
 * 네트워크에서 일어나는 일 — 연결 거부, 응답 지연, 절반만 도착한 응답 — 은 재현되지 않았습니다.
 *
 * <p>응답을 받지 못한 모든 경우를 {@link BankUnknownResultException}으로 모읍니다. 호출자는 그것을
 * 실패가 아니라 미확정으로 다룹니다. 조회는 예외입니다 — 조회가 실패하면 아무것도 확정하지 않고
 * "모른다"를 그대로 돌려줍니다.
 *
 * <p>근거: ADR-007, docs/05-technical-design.md §7·§10
 */
@Component
public class MockBankClient {

    private static final Logger log = LoggerFactory.getLogger(MockBankClient.class);

    private final RestClient restClient;

    MockBankClient(MockBankProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public void openAccount(UUID accountId, String accountNumberToken, long balance, String currency) {
        restClient
                .post()
                .uri("/mock-bank/accounts")
                .body(new OpenAccountRequest(accountId.toString(), accountNumberToken, balance, currency))
                .retrieve()
                .toBodilessEntity();
    }

    /** 출금. 응답을 받지 못하면 결과를 모릅니다. */
    public TransferResponse withdraw(String externalKey, String accountNumberToken, long amount) {
        return call(
                () -> restClient
                        .post()
                        .uri("/mock-bank/withdrawals")
                        .body(new WithdrawalRequest(externalKey, accountNumberToken, amount))
                        .retrieve()
                        .body(TransferResponse.class),
                "withdrawal " + externalKey);
    }

    public TransferResponse payout(String externalKey, UUID merchantId, long amount) {
        return call(
                () -> restClient
                        .post()
                        .uri("/mock-bank/payouts")
                        .body(new PayoutRequest(externalKey, merchantId.toString(), amount))
                        .retrieve()
                        .body(TransferResponse.class),
                "payout " + externalKey);
    }

    /**
     * 기관에 남은 기록을 조회합니다.
     *
     * <p>조회가 실패하면 빈 값이 아니라 {@link Optional#empty()}와 구분되는 실패를 알려야 하므로,
     * 실패는 예외로 던지고 호출자가 UNAVAILABLE로 다룹니다. "기록 없음"과 "물어보지 못함"을 같은
     * 값으로 만들면, 조회 장애가 곧 "돈이 안 나갔다"는 결론이 됩니다.
     */
    public Optional<String> withdrawalStatus(String externalKey) {
        return status("/mock-bank/withdrawals/" + externalKey);
    }

    public Optional<String> payoutStatus(String externalKey) {
        return status("/mock-bank/payouts/" + externalKey);
    }

    /**
     * 대사용 명세를 받아옵니다.
     *
     * <p>받지 못하면 예외입니다. 빈 목록을 돌려주면 "기관에 기록이 하나도 없다"가 되고, 대사는 우리
     * 쪽 기록 전부를 불일치로 올립니다. 명세를 못 받은 것과 기관에 기록이 없는 것은 다릅니다.
     */
    public List<StatementLine> withdrawalStatement(Instant from, Instant to) {
        return statement("/mock-bank/statements/withdrawals", from, to);
    }

    public List<StatementLine> payoutStatement(Instant from, Instant to) {
        return statement("/mock-bank/statements/payouts", from, to);
    }

    /**
     * 건별 기록입니다. 금액과 시각까지 옵니다.
     *
     * <p>타임라인이 기관의 표를 직접 읽던 자리를 대신합니다. 없으면 빈 값이고, 물어보지 못하면
     * 예외입니다 — 그 둘을 같은 값으로 만들면 "기관에 기록이 없다"는 잘못된 결론이 나옵니다.
     */
    public Optional<StatementLine> withdrawalRecord(String externalKey) {
        return record("/mock-bank/records/withdrawals/" + externalKey);
    }

    public Optional<StatementLine> payoutRecord(String externalKey) {
        return record("/mock-bank/records/payouts/" + externalKey);
    }

    private Optional<StatementLine> record(String uri) {
        StatementLine line = call(
                () -> restClient
                        .get()
                        .uri(uri)
                        .retrieve()
                        .onStatus(status -> status.value() == 404, (request, response) -> {})
                        .body(StatementLine.class),
                "record query " + uri);
        return Optional.ofNullable(line);
    }

    private List<StatementLine> statement(String path, Instant from, Instant to) {
        List<StatementLine> lines = call(
                () -> restClient
                        .get()
                        .uri(builder -> builder.path(path)
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .build())
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<StatementLine>>() {}),
                "statement " + path);
        return lines == null ? List.of() : lines;
    }

    /**
     * 기관의 장부와 장애 모드를 함께 초기화합니다.
     *
     * <p>기관이 자기 데이터베이스를 갖게 되면서 시험이 우리 JdbcTemplate으로 기관 표를 비울 수 없게
     * 됐습니다. 그것이 요점이고, 대신 기관에 부탁합니다.
     */
    public void resetInstitution() {
        restClient.post().uri("/mock-bank/admin/reset").retrieve().toBodilessEntity();
    }

    /** 시험이 기관 쪽 기록을 손보는 통로입니다. */
    public void amendWithdrawal(String externalKey, Long amount, boolean delete) {
        restClient
                .post()
                .uri("/mock-bank/admin/withdrawals/amend")
                .body(new AmendRequest(externalKey, amount, delete))
                .retrieve()
                .toBodilessEntity();
    }

    /** 우리에게 기록이 없는 외부 출금을 기관에 만듭니다(EXTERNAL_ONLY 시나리오). */
    public void insertOrphanWithdrawal(String externalKey, long amount, String accountNumberToken) {
        restClient
                .post()
                .uri("/mock-bank/admin/withdrawals/orphan")
                .body(new OrphanRequest(externalKey, amount, accountNumberToken))
                .retrieve()
                .toBodilessEntity();
    }

    /** 기관에 남아 있는 계좌 잔액 합계입니다. 시험은 계좌 토큰을 알지 못합니다(해시로 만들어집니다). */
    public long totalAccountBalance() {
        Long balance = restClient
                .get()
                .uri("/mock-bank/admin/accounts/balance-total")
                .retrieve()
                .body(Long.class);
        return balance == null ? 0L : balance;
    }

    public long accountBalance(String accountNumberToken) {
        Long balance = restClient
                .get()
                .uri("/mock-bank/admin/accounts/{token}/balance", accountNumberToken)
                .retrieve()
                .body(Long.class);
        return balance == null ? 0L : balance;
    }

    /** 기관에 남은 건수입니다. {@code table}은 withdrawals 또는 payouts입니다. */
    public long count(String table, String status) {
        Long value = restClient
                .get()
                .uri(builder -> {
                    var uri = builder.path("/mock-bank/admin/{table}/count");
                    if (status != null) {
                        uri = uri.queryParam("status", status);
                    }
                    return uri.build(table);
                })
                .retrieve()
                .body(Long.class);
        return value == null ? 0L : value;
    }

    /** 장애 주입을 기관에 전달합니다. 운영 환경에는 이 기관 자체가 없습니다. */
    public void setBehavior(Object behaviorRequest) {
        restClient
                .post()
                .uri("/mock-bank/admin/behavior")
                .body(behaviorRequest)
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

    private <T> T call(java.util.function.Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (RestClientException e) {
            // 연결 거부·읽기 타임아웃·5xx가 모두 여기로 옵니다. 공통점은 결과를 모른다는 것입니다.
            log.warn("mock bank call did not return a result: {}", what);
            throw new BankUnknownResultException("mock bank did not answer: " + what, e);
        }
    }

    /** 남은 것은 전송 형태뿐입니다. 기관은 우리 도메인 타입을 모릅니다. */
    record OpenAccountRequest(String accountId, String accountNumberToken, long balance, String currency) {}

    record WithdrawalRequest(String externalKey, String accountNumberToken, long amount) {}

    record PayoutRequest(String externalKey, String merchantId, long amount) {}

    record AmendRequest(String externalKey, Long amount, boolean delete) {}

    record OrphanRequest(String externalKey, long amount, String accountNumberToken) {}

    public record TransferResponse(boolean succeeded, String externalReferenceId, String failureReason) {}

    record StatusResponse(String status) {}

    /** 명세 한 줄입니다. 기관이 알려주는 사실만 담습니다. */
    public record StatementLine(String externalKey, String status, long amount, String currency, Instant occurredAt) {}

    /** 기관이 붙잡고 있을 시간을 설정하는 요청입니다. */
    public record BehaviorRequest(
            String withdrawalMode,
            String payoutMode,
            Boolean withdrawalStatusQueryAvailable,
            Boolean payoutStatusQueryAvailable,
            Boolean statementAvailable,
            Long hangForMillis,
            Boolean reset) {}
}
