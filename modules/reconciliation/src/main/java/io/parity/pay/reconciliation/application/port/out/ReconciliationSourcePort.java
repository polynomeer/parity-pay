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

    List<ExternalRecord> loadExternalWithdrawals(Instant windowStart, Instant windowEnd);

    List<InternalRecord> loadInternalPayouts(Instant windowStart, Instant windowEnd);

    List<ExternalRecord> loadExternalPayouts(Instant windowStart, Instant windowEnd);
}
