package io.parity.pay.reconciliation.application.port.out;

import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import java.time.Instant;
import java.util.List;

/**
 * 대사 대상 기록을 읽어오는 포트.
 *
 * <p>reconciliation 모듈은 다른 모듈의 테이블을 알지 못합니다. 어떤 테이블에서 어떻게 읽을지는
 * 조립 지점의 어댑터가 결정합니다. 근거: docs/05-technical-design.md §5
 */
public interface ReconciliationSourcePort {

    List<InternalRecord> loadInternalTopUps(Instant windowStart, Instant windowEnd);

    /**
     * 기관에서 출금 명세를 받아옵니다.
     *
     * <p>받지 못하면 {@link ReconciliationSourceUnavailableException}을 던집니다. 빈 목록으로
     * 돌려주면 안 됩니다 — 그러면 "기관에 기록이 없다"가 되고 우리 쪽 기록 전부가 불일치가 됩니다.
     */
    List<ExternalRecord> loadExternalWithdrawals(Instant windowStart, Instant windowEnd);

    List<InternalRecord> loadInternalPayouts(Instant windowStart, Instant windowEnd);

    /** 기관에서 지급 명세를 받아옵니다. 실패는 예외입니다. */
    List<ExternalRecord> loadExternalPayouts(Instant windowStart, Instant windowEnd);
}
