package io.parity.pay.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.event.WalletEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발행기 다중 인스턴스 동시 실행.
 *
 * <p>ADR-005는 여러 발행기가 같은 Outbox를 비워도 안전하다고 주장하고 근거로 {@code FOR UPDATE SKIP
 * LOCKED}를 듭니다. 이 테스트 전까지 그 주장의 근거는 코드뿐이었습니다.
 *
 * <p>인스턴스를 프로세스로 나누지 않고 스레드로 만듭니다. {@code SKIP LOCKED}가 구분하는 것은
 * 프로세스가 아니라 **DB 트랜잭션**이고, 스레드마다 커넥션과 트랜잭션이 따로이므로 잠금 관점에서는
 * 같은 상황입니다. 프로세스로 나눈 실험은 load-tests/multi-instance-experiment.py에 있습니다.
 *
 * <p>근거: ADR-005, docs/09-consistency-recovery.md §5
 */
@SpringBootTest
class OutboxMultiInstanceTest extends AbstractIntegrationTest {

    private static final int PUBLISHERS = 4;
    private static final int EVENTS = 400;

    /** 무한 반복을 막는 상한입니다. 정상이면 훨씬 적은 라운드에서 끝납니다. */
    private static final int MAX_ROUNDS = 5_000;

    /** 발행기별로 브로커에 넘긴 이벤트입니다. 두 발행기가 같은 이벤트를 넘겼는지 봅니다. */
    private final Map<String, List<UUID>> sentByPublisher = new ConcurrentHashMap<>();

    /** 브로커가 받은 순서입니다. 파티션 키별 순서 역전을 세기 위해 기록합니다. */
    private final List<UUID> arrivalOrder = java.util.Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger batchCount = new AtomicInteger();

    @MockitoBean
    private MessageBroker messageBroker;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxAppender outboxAppender;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE outbox_event");
        sentByPublisher.clear();
        arrivalOrder.clear();
        batchCount.set(0);

        given(messageBroker.sendAll(anyString(), anyList())).willAnswer(invocation -> {
            List<MessageBroker.OutboxMessage> messages = invocation.getArgument(1);
            batchCount.incrementAndGet();
            List<MessageBroker.SendOutcome> outcomes = new ArrayList<>(messages.size());
            List<UUID> mine = sentByPublisher.computeIfAbsent(
                    Thread.currentThread().getName(),
                    name -> java.util.Collections.synchronizedList(new ArrayList<>()));
            for (MessageBroker.OutboxMessage message : messages) {
                mine.add(message.eventId());
                arrivalOrder.add(message.eventId());
                outcomes.add(MessageBroker.SendOutcome.acknowledged(message.eventId()));
            }
            // 잠금이 열려 있는 창을 넓힙니다. 전송이 즉시 끝나면 발행기들이 서로 겹칠 틈이 없어
            // 동시성 테스트가 순차 실행과 구별되지 않습니다.
            Thread.sleep(5);
            return outcomes;
        });
    }

    @Test
    @DisplayName("발행기 4대가 같은 Outbox를 비워도 이벤트마다 정확히 한 대만 발행한다")
    void concurrentPublishersClaimDisjointBatches() throws Exception {
        List<UUID> seeded = seedEvents(EVENTS);

        drainConcurrently();

        // 1. 아무도 두 번 보내지 않았습니다. 이것이 SKIP LOCKED가 주장하는 바입니다.
        List<UUID> allSent =
                sentByPublisher.values().stream().flatMap(List::stream).toList();
        assertThat(allSent).hasSize(EVENTS).containsExactlyInAnyOrderElementsOf(seeded);

        // 2. 발행기끼리 배치가 겹치지 않았습니다.
        assertThat(sentByPublisher.values().stream().flatMap(List::stream).distinct())
                .hasSize(EVENTS);

        // 3. 아무것도 남지 않았습니다.
        assertThat(countByStatus("PUBLISHED")).isEqualTo(EVENTS);
        assertThat(countByStatus("PENDING")).isZero();
        assertThat(countByStatus("FAILED")).isZero();

        // 4. 모든 이벤트가 정확히 한 번 시도되었습니다. 재시도가 섞였다면 2 이상이 나옵니다.
        assertThat(jdbcTemplate.queryForObject("SELECT max(attempt_count) FROM outbox_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("발행기가 여러 대여도 같은 Aggregate 안의 순서는 유지된다")
    void orderWithinAnAggregateSurvivesMultipleInstances() throws Exception {
        // 한 Aggregate에 배치 크기를 훨씬 넘는 이벤트를 만듭니다. 발행기 여러 대가 이 이벤트들을
        // 나눠 가져가려 하는 상황이 순서가 깨지던 조건이었습니다.
        WalletId wallet = WalletId.of(UUID.randomUUID());
        List<UUID> seeded = seedEventsFor(wallet, EVENTS);

        drainConcurrently();

        Map<UUID, Integer> seedPosition = new HashMap<>();
        for (int i = 0; i < seeded.size(); i++) {
            seedPosition.put(seeded.get(i), i);
        }
        int inversions = 0;
        for (int i = 1; i < arrivalOrder.size(); i++) {
            if (seedPosition.get(arrivalOrder.get(i)) < seedPosition.get(arrivalOrder.get(i - 1))) {
                inversions++;
            }
        }

        assertThat(arrivalOrder).hasSize(EVENTS).doesNotHaveDuplicates();
        // 이 단언이 M-001에서 찾은 결함의 회귀 시험입니다. 선점 쿼리가 파티션 키별 선두만
        // 집어가기 전에는 같은 조건에서 400건 중 18건이 역전됐습니다.
        System.out.printf(
                "[M-001] publishers=%d batches=%d order-inversions=%d%n", PUBLISHERS, batchCount.get(), inversions);
        assertThat(inversions).isZero();
    }

    /** 발행기 4대를 같은 순간에 풀어놓고 Outbox가 빌 때까지 돌립니다. */
    private void drainConcurrently() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(PUBLISHERS)) {
            List<Future<Object>> futures = IntStream.range(0, PUBLISHERS)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        // 빈 배치를 받았다고 멈추지 않습니다. 파티션 키별 선두만 집어가므로, 남은
                        // 이벤트가 있어도 다른 발행기가 선두를 쥐고 있는 동안에는 빈 배치가 옵니다.
                        // 한 대가 먼저 다 비워버리는 것도 정상 결과입니다.
                        for (int round = 0; round < MAX_ROUNDS && countByStatus("PENDING") > 0; round++) {
                            outboxPublisher.publishBatch();
                        }
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<Object> future : futures) {
                future.get();
            }
        }
    }

    private List<UUID> seedEvents(int count) {
        return transactionTemplate.execute(status -> {
            List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                var envelope = WalletEvents.walletCreated(
                        WalletId.of(UUID.randomUUID()),
                        MemberId.generate(),
                        CurrencyCode.KRW,
                        Instant.now().plusMillis(i));
                outboxAppender.append(envelope);
                ids.add(envelope.eventId().value());
            }
            return ids;
        });
    }

    private List<UUID> seedEventsFor(WalletId wallet, int count) {
        return transactionTemplate.execute(status -> {
            List<UUID> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                var envelope = WalletEvents.walletCreated(
                        wallet,
                        MemberId.generate(),
                        CurrencyCode.KRW,
                        Instant.now().plusMillis(i));
                outboxAppender.append(envelope);
                ids.add(envelope.eventId().value());
            }
            return ids;
        });
    }

    private long countByStatus(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
    }
}
