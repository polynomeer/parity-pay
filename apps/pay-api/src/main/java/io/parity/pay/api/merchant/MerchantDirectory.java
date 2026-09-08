package io.parity.pay.api.merchant;

import io.parity.pay.api.member.MemberAccount;
import io.parity.pay.api.member.MemberAccountRepository;
import io.parity.pay.api.security.Role;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.security.CurrentPrincipal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 등록과 "지금 요청한 사람의 판매자 식별자" 조회.
 *
 * <p>판매자 API가 보는 {@code merchantId}는 **요청에서 오지 않습니다.** 토큰의 회원 ID로 조회합니다.
 * 요청 파라미터로 받으면 다른 판매자의 식별자를 적어 넣는 순간 남의 정산이 보입니다.
 *
 * <p>근거: docs/02-prd.md §6, docs/05-technical-design.md §11
 */
@Service
public class MerchantDirectory {

    private final MerchantRepository merchantRepository;
    private final MemberAccountRepository memberAccountRepository;
    private final CurrentPrincipal currentPrincipal;
    private final Clock clock;

    MerchantDirectory(
            MerchantRepository merchantRepository,
            MemberAccountRepository memberAccountRepository,
            CurrentPrincipal currentPrincipal,
            Clock clock) {
        this.merchantRepository = merchantRepository;
        this.memberAccountRepository = memberAccountRepository;
        this.currentPrincipal = currentPrincipal;
        this.clock = clock;
    }

    /**
     * 회원을 판매자로 등록하고 {@code MERCHANT} 역할을 부여합니다.
     *
     * <p>등록과 역할 부여는 하나의 트랜잭션입니다. 둘이 갈라지면 판매자 행은 있는데 조회할 수 없는
     * 계정이나, 역할만 있고 볼 정산이 없는 계정이 생깁니다.
     */
    @Transactional
    public Merchant register(String ownerEmail, String name) {
        MemberAccount owner = memberAccountRepository
                .findByEmail(ownerEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "the owner is not a known member"));
        if (!owner.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "the owner account is not active");
        }

        Merchant merchant = new Merchant(MerchantId.generate(), name, owner.memberId(), "ACTIVE", clock.instant());
        try {
            merchantRepository.insert(merchant, clock.instant());
        } catch (DuplicateKeyException e) {
            // 유니크 제약이 두 번째 방어선입니다. 조회 후 삽입 사이에 다른 요청이 끼어들 수 있습니다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "this member already represents a merchant");
        }

        Set<Role> roles = EnumSet.copyOf(owner.roles());
        roles.add(Role.MERCHANT);
        memberAccountRepository.updateRoles(owner.memberId(), roles);
        return merchant;
    }

    /**
     * 지금 요청한 사람의 판매자입니다.
     *
     * <p>역할만 있고 판매자 등록이 없거나 정지된 판매자면 거부합니다. 역할은 문이고 등록은 신원인데,
     * 둘 중 하나만으로 통과시키면 어느 쪽도 신뢰할 수 없게 됩니다.
     */
    @Transactional(readOnly = true)
    public Merchant requireCurrentMerchant() {
        MemberId memberId = currentPrincipal.memberId();
        Merchant merchant = merchantRepository
                .findByOwner(memberId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RISK_BLOCKED, "this account does not represent a merchant"));
        if (!merchant.isActive()) {
            throw new BusinessException(ErrorCode.RISK_BLOCKED, "this merchant account is suspended");
        }
        return merchant;
    }
}
