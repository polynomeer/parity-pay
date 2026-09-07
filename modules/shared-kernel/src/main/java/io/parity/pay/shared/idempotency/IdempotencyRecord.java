package io.parity.pay.shared.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * 저장된 멱등 요청 기록.
 *
 * <p>{@code businessReferenceId}는 응답을 재구성하기 위한 최소 정보입니다. 응답 본문 전체를 저장하지
 * 못해 금융 거래를 롤백하는 일이 없도록 업무 ID를 같은 트랜잭션에 남깁니다.
 * 근거: docs/09-consistency-recovery.md §3
 */
public record IdempotencyRecord(
        UUID principalId,
        String operation,
        IdempotencyKey key,
        String requestHash,
        IdempotencyStatus status,
        UUID businessReferenceId) {

    public Optional<UUID> businessReference() {
        return Optional.ofNullable(businessReferenceId);
    }

    public boolean isSettled() {
        return status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED;
    }
}
