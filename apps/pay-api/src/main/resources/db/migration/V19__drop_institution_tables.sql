-- 외부기관의 표를 우리 데이터베이스에서 내보냅니다.
--
-- 기관은 2026-09-08에 별도 프로세스가 됐지만 데이터는 여기 남아 있었습니다. 그 상태에서는 우리 쪽
-- 코드가 기관의 표를 조인할 수 있고, 실제로 대사가 그렇게 하고 있었습니다. 실제 기관에서는 할 수 없는
-- 일이며, 할 수 없어야 그 경로가 코드에 정직하게 드러납니다.
--
-- 이제 각 기관이 자기 데이터베이스와 자기 마이그레이션을 가집니다
-- (apps/mock-bank/.../V1__mock_bank.sql, apps/mock-pg/.../V1__mock_pg.sql).
--
-- 근거: docs/05-technical-design.md §10, reports/11 F-011
DROP TABLE IF EXISTS mock_bank_withdrawal;
DROP TABLE IF EXISTS mock_bank_payout;
DROP TABLE IF EXISTS mock_bank_account;
DROP TABLE IF EXISTS mock_pg_refund;
DROP TABLE IF EXISTS mock_pg_approval;
