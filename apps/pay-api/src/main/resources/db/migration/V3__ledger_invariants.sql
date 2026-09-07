-- 원장 불변조건을 DB에서 강제합니다.
-- 애플리케이션 버그, 배치 스크립트, 운영자 SQL 어느 경로로도 깨지지 않아야 합니다.
-- 근거: INV-001, INV-006, docs/07-ledger-journal-catalog.md §8

-- INV-001: 커밋 시점에 거래별 차변 합계와 대변 합계가 같아야 합니다.
-- 항목을 여러 번 INSERT하는 중간 상태를 허용해야 하므로 지연 제약 트리거를 사용합니다.
CREATE FUNCTION ledger_transaction_must_balance() RETURNS TRIGGER AS $$
DECLARE
    debit_total  BIGINT;
    credit_total BIGINT;
    entry_count  INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO debit_total, credit_total, entry_count
      FROM ledger_entry
     WHERE transaction_id = NEW.transaction_id;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'INV-001: ledger transaction % must have at least two entries',
            NEW.transaction_id;
    END IF;

    IF debit_total <> credit_total THEN
        RAISE EXCEPTION 'INV-001: ledger transaction % is unbalanced (debit=%, credit=%)',
            NEW.transaction_id, debit_total, credit_total;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tg_ledger_entry_balance
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger_transaction_must_balance();

CREATE CONSTRAINT TRIGGER tg_ledger_transaction_balance
    AFTER INSERT ON ledger_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger_transaction_must_balance();

-- INV-006: 확정 원장 항목은 수정·삭제하지 않습니다. 취소·보정은 새 분개입니다(ADR-009).
CREATE FUNCTION ledger_entry_is_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'INV-006: ledger_entry is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW
    EXECUTE FUNCTION ledger_entry_is_immutable();

-- 원장 거래는 삭제할 수 없고, POSTED 이후에는 status를 REVERSED로 표시하는 것 외의 변경을 막습니다.
CREATE FUNCTION ledger_transaction_is_immutable() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'INV-006: ledger_transaction cannot be deleted (%)', OLD.transaction_id;
    END IF;

    IF OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
       AND NEW.transaction_id = OLD.transaction_id
       AND NEW.transaction_type = OLD.transaction_type
       AND NEW.reference_type = OLD.reference_type
       AND NEW.reference_id = OLD.reference_id
       AND NEW.currency = OLD.currency
       AND NEW.effective_at = OLD.effective_at
       AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'INV-006: ledger_transaction % is immutable once posted', OLD.transaction_id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_transaction_immutable
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW
    EXECUTE FUNCTION ledger_transaction_is_immutable();
