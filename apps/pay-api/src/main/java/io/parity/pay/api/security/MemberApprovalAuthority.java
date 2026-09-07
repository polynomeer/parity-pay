package io.parity.pay.api.security;

import io.parity.pay.api.member.MemberAccount;
import io.parity.pay.api.member.MemberAccountRepository;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.security.ApprovalAuthority;
import org.springframework.stereotype.Component;

/**
 * 승인자 검증.
 *
 * <p>승인자는 이메일로 지정합니다. 사람을 가리키는 값이어야 감사 로그가 읽히기 때문입니다.
 * 존재하지 않거나, 승인 권한이 없거나, 요청자 자신이면 거부합니다.
 */
@Component
class MemberApprovalAuthority implements ApprovalAuthority {

    private final MemberAccountRepository memberAccountRepository;

    MemberApprovalAuthority(MemberAccountRepository memberAccountRepository) {
        this.memberAccountRepository = memberAccountRepository;
    }

    @Override
    public void requireDistinctApprover(String requesterId, String approverId) {
        if (approverId == null || approverId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "an approver is required");
        }
        if (approverId.equalsIgnoreCase(requesterId)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "an adjustment must be approved by someone other than the requester");
        }

        MemberAccount approver = memberAccountRepository
                .findByEmail(approverId)
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.INVALID_REQUEST, "the approver is not a known operator"));
        if (!approver.roles().contains(Role.OPS_APPROVER)) {
            throw new BusinessException(ErrorCode.RISK_BLOCKED, "the approver does not have approval authority");
        }
        if (!approver.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "the approver account is not active");
        }
    }
}
