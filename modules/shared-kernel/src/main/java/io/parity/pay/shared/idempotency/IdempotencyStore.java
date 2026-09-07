package io.parity.pay.shared.idempotency;

import java.util.UUID;

/**
 * 멱등 요청 저장소 포트.
 *
 * <p>키 범위는 {@code principalId + operation + key}입니다. 같은 키·다른 본문 해시는
 * {@code IDEMPOTENCY_KEY_REUSED}로 거부합니다. 근거: docs/04-payment-policy.md §5
 */
public interface IdempotencyStore {

    /**
     * 요청을 선점합니다.
     *
     * @return 이미 존재하던 기록이면 그 기록을, 이번에 새로 만든 요청이면 {@code PROCESSING} 기록을
     *     반환합니다. 호출자는 {@link IdempotencyRecord#status()}로 재사용 여부를 판단합니다.
     */
    IdempotencyRecord beginOrGet(UUID principalId, String operation, IdempotencyKey key, String requestHash);

    /** 업무 결과가 확정되면 상태와 업무 참조를 기록합니다. */
    void settle(
            UUID principalId, String operation, IdempotencyKey key, IdempotencyStatus status, UUID businessReferenceId);
}
