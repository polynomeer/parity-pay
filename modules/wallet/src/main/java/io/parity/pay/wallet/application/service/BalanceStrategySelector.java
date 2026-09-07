package io.parity.pay.wallet.application.service;

import org.springframework.stereotype.Component;

/**
 * 잔액 차감 전략 선택기.
 *
 * <p>ADR-004는 조건부 원자 갱신을 기본안으로 제안하면서, 비관적 잠금과 비교 측정한 뒤 확정하라고
 * 요구합니다. 두 구현을 같은 환경에서 번갈아 실행할 수 있어야 그 비교가 가능합니다.
 *
 * <p>기본값은 설정에서 오고, 실험 중에만 런타임으로 바꿉니다. 운영 중 임의 변경을 위한 장치가
 * 아닙니다. 근거: ADR-004, docs/11 P-002
 */
@Component
public class BalanceStrategySelector {

    private volatile Strategy current;

    public BalanceStrategySelector(WalletProperties properties) {
        this.current = properties.balanceStrategy();
    }

    public Strategy current() {
        return current;
    }

    /** 비교 측정을 위해 전략을 바꿉니다. */
    public void use(Strategy strategy) {
        this.current = strategy;
    }

    public enum Strategy {
        /** 잔액 조건을 WHERE에 넣은 단일 UPDATE를 JDBC로 실행합니다. */
        CONDITIONAL_UPDATE,
        /** 행을 먼저 잠그고 읽은 뒤 갱신합니다. 둘 다 JDBC이므로 배관 조건이 같습니다. */
        PESSIMISTIC_LOCK,
        /**
         * 같은 조건부 UPDATE를 JPA {@code @Modifying} 쿼리로 실행합니다.
         *
         * <p>비교 대상에 남겨둔 이유는, 처음 측정에서 이 경로가 눈에 띄게 느렸고 그 원인이 잠금
         * 전략이 아니라 영속성 컨텍스트 flush·clear라는 것을 보이기 위해서입니다.
         */
        CONDITIONAL_UPDATE_JPA
    }
}
