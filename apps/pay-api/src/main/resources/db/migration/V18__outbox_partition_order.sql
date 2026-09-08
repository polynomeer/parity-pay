-- 발행기 다중 인스턴스에서 Aggregate 내 순서를 지키기 위한 인덱스.
--
-- 발행기를 여러 대 돌리면 같은 결제의 승인·취소 이벤트가 서로 다른 배치로 나뉘어 나가고, 브로커
-- 도착 순서가 occurred_at 순서와 달라집니다(M-001 실험에서 400건 중 18건 역전). 설계는 이 순서에
-- 의존합니다(PaymentEvents javadoc, docs/05 §9).
--
-- 그래서 선점 쿼리가 파티션 키마다 "가장 앞선 미발행 이벤트"만 집어가도록 바꿉니다. 그 판정에
-- 쓰는 인덱스입니다. 부분 인덱스라 PENDING이 아닌 행은 들어가지 않습니다.
-- 근거: reports/11 M-001, ADR-005
CREATE INDEX ix_outbox_pending_partition
    ON outbox_event (partition_key, occurred_at, event_id)
    WHERE status = 'PENDING';
