package io.parity.pay.shared.security;

/**
 * 이중 승인 확인.
 *
 * <p>금액을 움직이는 운영 작업은 요청자와 승인자가 달라야 합니다. 승인자가 실제로 존재하고 승인
 * 권한을 가진 사람인지 확인하는 책임을 조립 지점에 둡니다. 업무 모듈은 규칙만 알고 사용자
 * 저장소를 알지 못합니다.
 *
 * <p>근거: docs/09-consistency-recovery.md §10, docs/05-technical-design.md §11
 */
public interface ApprovalAuthority {

    /**
     * 승인자를 검증합니다.
     *
     * @throws io.parity.pay.shared.error.BusinessException 승인자가 없거나, 승인 권한이 없거나,
     *     요청자와 같은 사람일 때
     */
    void requireDistinctApprover(String requesterId, String approverId);
}
