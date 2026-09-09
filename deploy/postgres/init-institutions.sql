-- 외부기관용 데이터베이스를 만듭니다.
--
-- 기관은 우리와 **다른 데이터베이스**를 씁니다. 같은 곳에 두면 우리 쪽 코드가 기관의 표를 조인할 수
-- 있고, 실제로 대사와 타임라인이 그렇게 하고 있었습니다(2026-09-09에 분리). 실제 기관에서는 할 수
-- 없는 일이며, 할 수 없어야 그 경로가 코드에 정직하게 드러납니다.
--
-- 이 스크립트는 볼륨이 비어 있을 때만 실행됩니다. 이미 만들어 둔 로컬 환경이라면
-- `docker compose down -v`로 지우고 다시 올리거나, 아래 두 줄을 직접 실행하십시오.
--
-- 근거: docs/05-technical-design.md §10
CREATE DATABASE paritypay_bank OWNER paritypay;
CREATE DATABASE paritypay_pg OWNER paritypay;
