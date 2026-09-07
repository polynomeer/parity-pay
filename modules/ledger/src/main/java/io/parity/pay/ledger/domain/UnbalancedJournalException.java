package io.parity.pay.ledger.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;

/**
 * 차변과 대변이 맞지 않는 분개를 만들려고 할 때 발생합니다.
 *
 * <p>이 예외는 사용자 입력 오류가 아니라 프로그래밍 오류이므로 500으로 매핑되며, 발생 시 한 건이라도
 * 즉시 경보 대상입니다. 근거: INV-001, docs/05-technical-design.md §12
 */
public class UnbalancedJournalException extends BusinessException {

    public UnbalancedJournalException(String message) {
        super(ErrorCode.INTERNAL_ERROR, message);
    }
}
