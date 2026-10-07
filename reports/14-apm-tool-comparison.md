# APM 도구 비교 실험 보고서 (RPT-04)

> **에이전트 지침**
> - **읽는 시점**: APM 도구를 붙이거나 바꿀 때, 에이전트 오버헤드·설치 비용을 따질 때.
> - **이 문서가 정하는 것**: 아무것도 정하지 않습니다. 실측 기록입니다. 교체 구조의 결정은 [ADR-017](../docs/adr/017-swappable-apm-backend.md)에 있습니다.
> - **강제 규칙**: 여기 수치를 다른 환경의 성능으로 옮겨 적지 않습니다. 재실행은 `scripts/apm.sh` + `load-tests/apm-overhead-experiment.py`로 하고 원본 경로를 함께 남깁니다. **못 한 것은 못 했다고 적습니다.**

## 1. 요약

같은 애플리케이션·같은 부하·같은 기계에 **APM 백엔드만 바꿔 가며** 붙여 봤습니다. 애플리케이션 코드는
어떤 도구도 알지 못합니다(ADR-017) — 계측은 OpenTelemetry 자동계측 Java 에이전트가 하고, 바뀌는 것은
컬렉터 설정 하나이거나(OTLP 계열) `-javaagent` 한 줄입니다(자체 에이전트 계열).

- **에이전트를 켜는 비용이 작지 않습니다.** OTel 에이전트에서 처리량 **−18.7%**(번갈아 측정), p50 18 → 21 ms,
  p95 29 → 38 ms. SkyWalking 자체 에이전트는 같은 방식으로 **−42.7%**, p50 25 → 41 ms였습니다.
- **측정 순서가 결과를 바꿉니다.** 팔별로 몰아서 돌린 첫 측정은 −15.9%였고, 번갈아 돌리자 −18.7%였습니다.
  이 저장소가 M-007에서 겪은 것과 같은 함정입니다.
- **설치 비용은 도구마다 자릿수가 다릅니다.** Jaeger·Tempo는 컨테이너 1개에 설정 한 블록이고, SigNoz는
  컨테이너 6개(약 970 MiB)에 전용 설치기, SkyWalking은 컨테이너 3개에 **기동 실패 세 번**이었습니다.
- **원인까지는 Jaeger가 두 단계**였습니다 — 트레이스 목록 → 상세에서 `POST /api/v1/payments` 2.52초 중
  외부 호출 `POST` 2.506초(**99.4%**)가 바로 보입니다. Zipkin도 같은 두 단계이고, 같은 에이전트가
  만든 같은 트레이스라 **스팬 수(20)와 구간이 Jaeger와 일치합니다**.
- **세 팔을 한 묶음에서 번갈아 돌렸습니다**(2차). none·Jaeger·Zipkin에서 **−9.2%**와 **−12.1%**입니다.
  1차의 −18.7%와 **합쳐 읽으면 안 됩니다** — 다른 날 다른 묶음입니다(§5.4).
- **아무도 세지 않는 비용이 하나 있습니다.** OTel Collector 자신이 전량 샘플링 70초에 **1.3~2.6 GiB**까지
  자랍니다. 상한을 두지 않았고, 팔을 바꿔도 다시 띄우지 않으므로 3.8 GiB까지 누적됐습니다(§5.5).
- **Pinpoint가 떴습니다**(2026-10-07, 두 번째 시도). 막은 것 일곱 개를 뚫었고, 마지막 둘은
  **미리 쪼개는 리전 수를 줄이는 것**과 **ZooKeeper tickTime을 올리는 것**이었습니다(§4, DOC-22 §3.5).
  서버맵에 USER → pay-api → PostgreSQL·외부기관이 **자동으로 그려집니다** — 다른 도구와 보는 단위가
  가장 다릅니다(§6).
- **SigNoz가 수집하지 않은 진짜 이유를 찾았습니다.** 스키마 마이그레이션이 아니라 **조직이 없어서**였습니다.
  첫 관리자 계정이 만들어지기 전에는 OpAMP 서버가 ingester를 등록하지 못하고, ingester는 **OTLP 포트를
  아예 열지 않습니다**(§4). 계정 생성은 사용자 몫이라 여기서 멈춥니다.
- **못 한 것**: SigNoz 수집·UI(계정 필요), Datadog(체험판 계정 필요), SkyWalking 트레이스 화면 캡처,
  Pinpoint 오버헤드의 **믿을 만한 수치**(§5.6 — 대조군이 한 묶음 안에서 4.7배 흔들렸습니다).

## 2. 환경

| 항목 | 값 |
|---|---|
| 커밋 | 1차 `3e4a547`, 2차(Zipkin·Tempo 화면) `77c74c3`, 3차(Pinpoint 기동·SigNoz 원인) `9076586` |
| 측정 일시 | 2026-10-05 ~ 2026-10-07 |
| 기계 | Apple M1 Max, 32 GB, macOS 26.6.2, Docker Desktop 28.0.4 (**Docker 할당 7.7 GiB**) |
| 애플리케이션 | pay-api (Java 21, `local` 프로필), 호스트 프로세스. 의존: postgres 17·Redpanda·mock-bank·mock-pg(컨테이너) |
| 부하 | k6 v1.0.0. 오버헤드는 `payment-baseline.js`(VU 20, P-001과 같은 조건), 가시성은 `external-pg-load.js` |
| 계측 | OpenTelemetry Java agent **2.12.0**, SkyWalking Java agent **9.7.0**, Pinpoint Java agent **3.1.1**, OTel Collector **0.119.0** |
| 백엔드 | Jaeger all-in-one **1.65.0**, Zipkin **3.6.1**, Grafana Tempo **2.7.0**(화면은 Grafana **11.5.1**), SigNoz(foundryctl **v0.3.0**이 생성), SkyWalking OAP·UI **10.4.0-java21** + BanyanDB **0.10.3**, Pinpoint **3.1.1**(HBase·Collector·Web) |
| 잡음 | **이 기계에는 다른 프로젝트의 컨테이너가 20여 개 떠 있습니다.** 절대값이 아니라 같은 실행 안의 상대 비교만 씁니다 |

> 측정 중 이 기계의 Docker Hub 이미지 내려받기가 매우 느렸습니다 — SkyWalking OAP+UI 약 19분,
> BanyanDB 0.10.3은 8분 넘게 진척이 없었고, SigNoz 이미지 중 하나는 `lease does not exist`로 실패해
> `docker builder prune -af`(16 GB 회수) 뒤에 받았습니다. 소요 시간 수치에는 이 조건이 섞여 있습니다.

## 3. 방법

```text
                                   ┌─ collector-jaeger.yaml  → Jaeger   (OTLP)
                                   ├─ collector-zipkin.yaml  → Zipkin   (Zipkin 형식)
pay-api ──(OTLP 4317)──▶ Collector ─┼─ collector-tempo.yaml   → Tempo    (OTLP) → 화면은 Grafana
   ▲ -javaagent (OTel)              └─ collector-signoz.yaml  → SigNoz   (OTLP)
   └── 자체 에이전트 계열은 이 자리를 바꿔 끼움 (SkyWalking · Pinpoint)
```

Zipkin만 exporter가 OTLP가 아닙니다 — Zipkin은 자기 형식만 받습니다. **그래도 앱은 바뀌지 않습니다.**
형식을 맞추는 일이 컬렉터 안에서 끝나는 것이 이 구조가 지키려던 성질입니다(ADR-017).

- 전환은 `scripts/apm.sh up <name>`입니다. 그것이 `.apm/env`를 적고, 앱을 띄우는 쪽이 그 파일을 읽습니다.
- **OTLP 계열끼리의 전환에는 앱 재시작이 필요 없습니다**(컬렉터 설정만 바뀜). 자체 에이전트 계열은 필요합니다.
- 오버헤드는 `load-tests/apm-overhead-experiment.py`가 잽니다. 팔마다 앱을 새로 띄우고 k6를 돌립니다.
- 가시성은 Mock PG를 `HANG_BEFORE_PROCESSING` **2,500 ms**로 두고(읽기 타임아웃 3초 아래 — 타임아웃이 아니라
  "느린 성공"을 만들기 위해서입니다) `external-pg-load.js`로 부하를 겁니다.

## 4. 설치 비용 — 실측

| 도구 | 컨테이너 | 저장소 | 첫 기동까지 막힌 횟수 | 메모리 |
|---|---:|---|---:|---|
| Jaeger (기준선) | 1 (+컬렉터) | 메모리 | 0 | 미측정 |
| **Zipkin** | **1** (+컬렉터) | 메모리 | **0** | **약 157 MiB**(부하 뒤 유휴) |
| Grafana Tempo | 1 (+컬렉터) | 로컬 디스크 | 0 | 약 157 MiB(부하 뒤 유휴). **화면을 보려면 Grafana가 더 필요합니다** |
| SigNoz | **6** | ClickHouse + Keeper + PostgreSQL | 2 (아래, **수집은 못 염**) | **약 840 MiB** (ClickHouse 640 MiB) |
| SkyWalking | **3** | BanyanDB(필수) | **3** (아래) | 미측정 |
| **Pinpoint** | **6** | HBase + MySQL + ZooKeeper + Redis | **7** (아래, 전부 뚫음) | 약 3.2 GiB |
| Datadog | — | SaaS | — | **미착수**(체험판 계정 필요) |

컨테이너 수와 막힌 횟수가 같이 움직입니다. 한 개짜리(Jaeger·Zipkin)는 한 번에 떴고, 다섯·여섯 개짜리는
전부 막혔습니다.

**Zipkin에서 막힌 것 — 없습니다.** 비교에서 가장 쉬웠습니다. 컨테이너 하나(`openzipkin/zipkin:3.6.1`)에
저장소는 메모리이고, 받자마자 조회됩니다. 다만 **OTLP를 받지 않습니다** — 자기 형식만 받으므로 컬렉터의
exporter만 `zipkin`으로 바꿉니다(`deploy/observability/otel/collector-zipkin.yaml`). 앱은 이 사실을
모릅니다. Jaeger와 같은 이유로 메모리 상한(`MEM_MAX_SPANS=500000`)과 `mem_limit`을 함께 둡니다.

**SigNoz에서 막힌 것 둘 — 그리고 두 번째가 진짜였습니다.** 2025년에 docker-compose 설치가 폐기되고
전용 설치기(`foundryctl`)로 옮겼습니다. 공식 안내는 `curl … | bash`이고, 이 실험은 같은 일을 단계로
나눠 했습니다 — 공식 GitHub 릴리스의 tarball과 checksums를 받아 **sha256을 검증한 뒤** 설치합니다
(`scripts/apm.sh`). 생성된 compose는 4317·4318·8080을 요구해 우리 컬렉터와 부딪히므로 호스트 포트를
다시 매핑합니다.

*첫 번째(2026-10-06).* ClickHouse 분산 DDL 스키마 마이그레이션이 10분 넘게 끝나지 않았습니다. 컨테이너를
recreate 했더니 클러스터 메타데이터에 죽은 복제본 호스트가 남아(`Cannot resolve host (a39b5c2f3c57)`)
마이그레이션이 영원히 끝나지 않는 상태가 됐습니다. **볼륨까지 지우고 한 번에 올려야 합니다.**

*두 번째(2026-10-07).* 볼륨을 지우고 한 번에 올렸더니 마이그레이터가 **2분 안에 `Exited (0)`**으로
끝나고 `signoz_traces`에 테이블 36개가 생겼습니다. **그런데도 트레이스는 0건이었습니다.**

```text
우리 컬렉터 --(OTLP)--> signoz-ingester:4317   ← 아무도 듣고 있지 않음
                             ▲ OpAMP 로 파이프라인 설정을 받아야 포트를 연다
                        signoz-signoz-0  ← "failed to find or create agent"
                             ▲ agent 레코드는 조직(organization)에 속한다
                        organizations = 0   ← 첫 관리자 계정을 만들어야 생긴다
```

확인한 것은 넷입니다. ① 우리 컬렉터가 `host.docker.internal:4327`에 연결하지 못함, ② 호스트에서 그 포트가
**닫혀 있음**, ③ ingester가 듣는 포트는 `8888`(자기 지표)뿐, ④ 메타스토어에 `organizations = 0`,
`users = 0`이고 SigNoz 서버 로그에 `failed to find or create agent`.

**SigNoz는 첫 관리자 계정이 만들어지기 전까지 아무것도 수집하지 않습니다.** 화면을 못 보는 것이 아니라
수집 경로가 열리지 않습니다. 계정 생성은 사용자 몫이므로 여기서 멈췄습니다 — 남은 것은 UI를 열고 계정을
만드는 한 번뿐이고, 그 순간 조직이 생기면서 ingester가 포트를 엽니다.

**SkyWalking에서 막힌 것 셋.**

| 시도 | 결과 |
|---|---|
| OAP 10.4 + `SW_STORAGE=h2` | `no provider found for module storage`로 **종료**. 10.x 이미지의 `oap-libs`에는 `storage-banyandb-plugin`만 있습니다 — **메모리 저장소가 없습니다** |
| OAP 10.4 + BanyanDB `0.11.1`(최신) | `Incompatible BanyanDB server API version: 0.11. But accepted versions: 0.10`으로 **종료** |
| OAP 10.4 + BanyanDB `0.10.3` | 앞 버전이 쓴 볼륨을 읽지 못해 **종료**(`unknown field "created…"`). 볼륨을 지우고서야 기동 |

**Pinpoint에서 막힌 것 일곱 — 그리고 떴습니다.** 과정은 [DOC-22 §3.5](../docs/22-apm-troubleshooting-log.md)에
자세히 있고, 여기에는 결과만 적습니다.

| # | 막은 것 | 고친 방법 |
|---|---|---|
| 1 | `depends_on`은 "컨테이너가 떴다"까지만 본다. HBase 초기화 중 collector·web이 죽음 | `restart: unless-stopped` |
| 2 | HBase 이미지에 `zoo1,zoo2,zoo3`이 **박혀 있음**. 다른 이름이면 테이블조차 못 만듦 | 컨테이너 하나에 별칭 셋 |
| 3 | HBase 주소와 클러스터 조정이 **한 변수를 공유** (`hbase.client.host=${pinpoint.zookeeper.address}`) | 둘 다 `zoo1` |
| 4 | 공식 예시의 MySQL 비밀번호가 `admin/admin` | `apm.sh`가 생성해 `.apm/`에 보관(ADR-011) |
| 5 | `pinpoint-hbase`가 **amd64 단일 아키텍처**. 에뮬레이션에서 **리전 1,900개가 넘는 스키마**를 만들다 마스터가 죽음 | `NUMREGIONS => 4`, 명시적 `SPLITS` 제거 |
| 6 | 세션 시간을 올려도 **ZooKeeper 서버가 깎음**(상한 = 20 × tickTime). 이미지에 `ZOO_MAX_SESSION_TIMEOUT` 변수가 **없음** | `ZOO_TICK_TIME=15000` + `zookeeper.session.timeout=300000` |
| 7 | 초기화 스크립트가 스키마 파일을 `sed -i`로 고쳐 바인드 마운트가 막힘 | 다른 자리에 두고 entrypoint에서 복사 |

5번과 6번을 **함께** 고치자 테이블 22개가 약 4분 30초에 전부 만들어졌고,
`/api/applications`가 `pay-api`를 돌려줬습니다. 5번만 고쳤을 때는 16개에서, 6번만 고쳤을 때는 4개에서
멈췄습니다 — **둘 다 필요했습니다.**

줄인 것은 리전 수뿐입니다(`deploy/observability/pinpoint/hbase-create.hbase`). 테이블 이름·컬럼
패밀리·TTL은 원본 그대로이고, 이 구성은 **한 노드 실험 스택을 띄우기 위한 것이지 성능을 재기 위한
것이 아닙니다.**

**Jaeger에서 막힌 것.** 기본 all-in-one은 메모리 저장소에 상한이 없어, 전량 샘플링 부하에서 자라다가
컨테이너가 **OOM으로 두 번 죽었습니다**(`Exited (137)`). `MEMORY_MAX_TRACES=20000` + `mem_limit: 1g`로
묶었습니다. 그러고 나면 이번에는 **보관이 수 분**입니다 — 1시간 조회가 0건이고 방금 넣은 부하만 보입니다.
배경 작업(Outbox 발행기·복구·불변조건 지표)이 쉬지 않고 1-스팬 트레이스를 만들어 링버퍼를 밀어내기 때문입니다.

## 5. 에이전트 오버헤드 — 실측

부하는 `payment-baseline.js`(VU 20, 서로 다른 지갑, 측정 창 70초)입니다. 팔마다 3회.

### 5.1 OTel 에이전트 (번갈아 측정, 채택)

| 팔 | 승인 건수 (3회) | 중앙값 | p50 ms | p95 ms | 실패 |
|---|---|---:|---:|---:|---:|
| none | 64,499 / 60,868 / 62,580 | **62,580** | 18 | 29 | 0 |
| OTel + Jaeger | 39,954 / 55,754 / 50,897 | **50,897** | 21 | 38 | 0 |

**처리량 −18.7%, p50 +3 ms, p95 +9 ms.** 원본: `reports/data/14-apm-lab/overhead-interleaved-none-jaeger-20261006.json`

### 5.2 순서가 결과를 바꿉니다

| 측정 방식 | none | OTel+Jaeger | 차이 |
|---|---:|---:|---:|
| 팔별로 몰아서 (처음, **틀린 방식**) | 64,519 | 54,269 | −15.9% |
| 번갈아 (다시, 채택) | 62,580 | 50,897 | **−18.7%** |

같은 하니스·같은 부하인데 3퍼센트포인트가 움직였습니다. 이 저장소는 M-007에서 **코드와 무관한 4.8배**를
같은 방식으로 만든 적이 있고, 그래서 `load-tests/README.md`에 "번갈아 돌린다"가 규칙으로 적혀 있었습니다.
처음 측정에서 그 규칙을 어겼고, 다시 돌려 고쳤습니다. 몰아서 돌린 결과도 함께 남깁니다
(`overhead-grouped-none-jaeger-tempo-20261006.json`).

**검산 하나.** 같은 묶음에서 백엔드만 Tempo로 바꾼 팔은 54,269 vs 53,944로 0.6% 차이였습니다. 에이전트가
같으므로 같아야 맞고, 실제로 같았습니다 — 이 하니스가 재는 것이 **백엔드가 아니라 에이전트 비용**이라는 뜻입니다.

### 5.3 SkyWalking 자체 에이전트 (번갈아 측정)

| 팔 | 승인 건수 (3회) | 중앙값 | p50 ms | p95 ms | 실패 |
|---|---|---:|---:|---:|---:|
| none | 42,769 / 33,757 / 49,625 | **42,769** | 25 | 54 | 0 |
| SkyWalking 9.7.0 | 24,503 / 32,551 / 23,183 | **24,503** | 41 | 97 | 0 |

**처리량 −42.7%, p50 +16 ms, p95 +43 ms.** 원본: `overhead-interleaved-none-skywalking-20261006.json`

**이 수치를 5.1과 나란히 빼서 비교하지 마십시오.** 두 측정은 다른 시각에 돌았고, 대조군 자체가
62,580 → 42,769으로 떨어져 있습니다(그 사이 BanyanDB·OAP 컨테이너가 떠 있었고 기계가 더 바빴습니다).
각 묶음 **안에서의 짝 비교**만 유효합니다. 세 팔을 한 묶음에서 번갈아 돌린 측정은 하지 못했습니다.

### 5.4 세 팔을 한 묶음에서 (2차, 2026-10-06)

1차에서 "세 팔을 한 묶음에서 번갈아 돌린 측정은 하지 못했습니다"라고 적어 둔 것을 했습니다.
none·Jaeger·Zipkin을 `none → jaeger → zipkin`을 한 라운드로 세 번 돌렸습니다.

| 팔 | 승인 건수 (3회) | 중앙값 | p50 ms | p95 ms | 대조군 대비 | 실패 |
|---|---|---:|---:|---:|---:|---:|
| none | 53,403 / 53,518 / 55,909 | **53,518** | 20 | 38 | — | 0 |
| OTel + Jaeger | 50,898 / 46,207 / 48,587 | **48,587** | 22 | 42 | **−9.2%** | 0 |
| OTel + Zipkin | 47,063 / 48,645 / 45,083 | **47,063** | 23 | 45 | **−12.1%** | 0 |

원본: `reports/data/14-apm-lab/overhead-interleaved-none-jaeger-zipkin-20261006.json`

**1차의 −18.7%와 이 −9.2%를 합쳐 읽지 마십시오.** 같은 하니스·같은 부하·같은 기계지만 다른 날 다른
묶음이고, 대조군 자체가 62,580에서 53,518로 다릅니다. 한 묶음 안의 짝 비교만 유효하다는 §5.3의 경고가
이번에도 그대로 적용됩니다. **같은 측정을 다른 날 다시 하면 9%와 19% 사이에서 움직인다**는 것이
이 두 묶음이 함께 말하는 것입니다.

**Zipkin이 Jaeger보다 3.1% 낮습니다.** 에이전트가 같으므로 원칙적으로 같아야 합니다(§5.2의 Tempo 검산은
0.6%였습니다). 다만 각 팔의 3회 편차가 그보다 큽니다 — Jaeger는 46,207~50,898(10%), Zipkin은
45,083~48,645(8%)입니다. **3회로는 3%를 가릴 수 없습니다.** 차이가 있다고 말하려면 횟수를 늘려야 하고,
그것은 하지 않았습니다. 대조군(53,403~55,909, 4.7%)이 두 처리군보다 안정적이라는 점은 눈에 띄지만
이것도 3회의 관찰입니다.

### 5.5 아무도 세지 않는 비용: 컬렉터 자신

하니스가 팔마다 APM 컨테이너의 메모리를 함께 적습니다. 그 숫자가 뜻밖이었습니다.

| 팔 | run 1 | run 2 | run 3 |
|---|---:|---:|---:|
| none | 47.6 MiB | 3.705 GiB | 3.834 GiB |
| OTel + Jaeger | 1.659 GiB | 1.318 GiB | 1.666 GiB |
| OTel + Zipkin | 2.582 GiB | 2.628 GiB | 2.371 GiB |

**OTel Collector 한 컨테이너의 값입니다.** 전량 샘플링으로 70초를 받으면 GiB 단위로 자랍니다.
`none` 팔의 첫 값이 47.6 MiB이고 그 뒤가 3.7 GiB인 이유는, `apm.sh up none`이 컨테이너를 건드리지
않기 때문입니다 — 앞 팔에서 자란 컬렉터가 그대로 남아 누적됩니다.

읽을 때 주의할 것이 둘 있습니다. 첫째, Jaeger와 Zipkin에는 `mem_limit`을 걸어 뒀지만 **컬렉터에는
걸지 않았습니다.** 상한이 없으니 자란 것이고, 이 값은 "필요한 양"이 아니라 "허용된 양"입니다.
둘째, Zipkin 경로가 Jaeger 경로보다 꾸준히 큽니다(2.4~2.6 GiB 대 1.3~1.7 GiB). exporter가 OTLP를
Zipkin JSON으로 바꾸는 일이 끼어 있어 그럴듯하지만, **그 인과는 확인하지 않았습니다.**

도구를 고르는 자리에서 비교하는 것은 보통 백엔드의 메모리입니다. 그런데 OTLP 경로에서는 **컬렉터가
백엔드보다 큽니다** — Tempo와 Zipkin이 부하 뒤 유휴에서 157 MiB쯤일 때 컬렉터는 GiB 단위였습니다.

### 5.6 Pinpoint 자체 에이전트 — 쟀지만 믿을 수 없습니다

| 팔 | 승인 건수 (3회) | 중앙값 | p50 ms | p95 ms | 실패 |
|---|---|---:|---:|---:|---:|
| none | 43,232 / 25,397 / 9,265 | 25,397 | 39 | 96 | 0 |
| Pinpoint 3.1.1 | 13,519 / 10,019 / 7,801 | 10,019 | 62 | 332 | 0 |

중앙값끼리는 −60.6%이고 p95는 96 → 332 ms입니다. **그런데 이 숫자를 쓰지 마십시오.**
**대조군이 한 묶음 안에서 43,232에서 9,265로 4.7배 떨어졌습니다.** 번갈아 돌렸는데도 그렇습니다.
팔이 아니라 **시간이** 지배하고 있다는 뜻이고, 이 저장소는 그런 상태의 수치를 결론으로 쓰지
않습니다(M-007).

원인으로 짐작되는 것은 Pinpoint 백엔드 자신입니다. 컨테이너 여섯 개가 같은 기계에서 돌고, HBase가
들어오는 스팬을 계속 쓰고 있습니다. 즉 이 측정에는 **에이전트 비용과 "백엔드를 같은 기계에 올린
비용"이 섞여 있습니다.** 짝 비교도 −69%·−61%·−16%으로 흔들립니다.

말할 수 있는 것은 방향뿐입니다. **세 쌍 모두에서 Pinpoint 쪽이 낮았고, p95가 세 자리로 올라갔습니다.**
크기를 말하려면 백엔드를 다른 기계에 두고 다시 재야 하고, 그것은 하지 않았습니다.
원본: `reports/data/14-apm-lab/overhead-interleaved-none-pinpoint-20261007.json`

## 6. 같은 장애에서 무엇이 보이는가 — 실측

Mock PG가 승인 전에 2.5초를 붙잡는 조건에서 결제 3 rps + 잔액 조회 5 rps를 걸었습니다. 클라이언트가 본
결제 지연은 중앙값 2.52초였습니다.

| 도구 | 원인까지 | 화면에서 보이는 것 |
|---|---|---|
| **Jaeger** | **2단계** (목록 → 상세) | 루트 `POST /api/v1/payments` **2.52 s**, 자식 `POST`(외부 호출) **2.506 s** — 전체의 99.4%. DB 스팬은 µs 단위. 트레이스당 20~21 스팬 |
| **SkyWalking** | 1단계 (대시보드) | 서비스 평균 응답시간·Apdex·엔드포인트별 부하/지연이 **대시보드 첫 화면**에 바로 나옵니다. 다만 "어느 호출이 느린가"는 엔드포인트 단위이고, 스팬 수준은 트레이스 화면으로 들어가야 합니다 |
| **Zipkin** | **2단계** (목록 → 상세) | Jaeger와 같습니다. Duration **2.525 s**, Services 1, Total Spans **20**, 자식 `post` 2.508 s. 같은 에이전트가 만든 같은 트레이스이므로 **숫자가 일치합니다** — 다른 것은 화면뿐입니다. 오른쪽에 스팬별 태그(`http.route`, `http.response.status_code=201` 등)가 함께 붙습니다 |
| **Tempo** | **3단계** (Grafana → Explore → 트레이스) | 화면이 **없습니다**. Grafana의 Explore에서 TraceQL `{ name="POST /api/v1/payments" }`로 찾습니다. 결과는 같습니다(2.53 s, 20 spans, 201). 대신 **Grafana를 함께 운영해야 합니다** |
| SigNoz | — | 수집 경로가 열리지 않았습니다 — 첫 관리자 계정이 필요합니다(§4) |
| **Pinpoint** | **1단계** (서버맵) | 애플리케이션을 고르면 **USER → pay-api → PostgreSQL·외부기관**이 이미 그려져 있습니다. 호출 수와 평균 지연이 화살표에 붙고(1,527건 898 ms / DB 7,285건 4 ms / 기관 513건 2 ms), 오른쪽 산점도에 **2.5초 무리와 0 근처 무리가 갈라져** 보입니다. Apdex 0.82, Max 3.06초 |

**부수 효과 하나.** SkyWalking은 자체 에이전트가 의존성까지 토폴로지에 올립니다 — `listServices`에
`pay-api` 외에 `localhost:5435`(PostgreSQL)와 `localhost:9092`(Kafka)가 서비스로 잡혔습니다. OTel 에이전트 +
Jaeger 조합에서는 PostgreSQL·Kafka가 별도 서비스로 올라오지 않습니다(DB 호출은 `pay-api` 안의 스팬입니다).
**같은 요청을 보는 방식 자체가 다릅니다.** 다만 Jaeger UI의 서비스 선택기는 `Service (2)`로 두 개를 셉니다 —
두 번째가 무엇인지(Jaeger 자신일 가능성이 큽니다)는 확인하지 않았습니다.

**부수 효과 둘.** SkyWalking 대시보드의 "Endpoint Avg Response Time" 상위는 결제 엔드포인트가 아니라
`SpringScheduled/…OutboxPublisher`(63,608 ms)와 `…InvariantMe…`(27,577 ms)입니다. 스케줄 작업 한 번이
엔드포인트 한 건으로 집계되기 때문입니다. **느린 요청을 찾으러 와서 처음 보는 것이 배치 작업입니다.**

### 화면 캡처

| 파일 | 무엇 |
|---|---|
| `apm-lab-jaeger-search.png` | 트레이스 목록 — `POST /api/v1/payments` 20건이 2.52~2.53초, 트레이스당 20~21 스팬, 산점도 |
| `apm-lab-jaeger-trace.png` | 트레이스 상세 — Duration 2.52 s, Depth 4, Total Spans 20. 외부 호출 `POST` 스팬이 전체 구간을 차지하고 DB 스팬은 µs |
| `apm-lab-skywalking-dashboard.png` | SkyWalking `General-Root` 서비스 대시보드 — Apdex 0.757, 평균 응답 804 ms, 7,569 calls/min, 엔드포인트별 지표 |
| `apm-lab-zipkin-search.png` | Zipkin 트레이스 목록 — 20건, 전부 2.52~2.53초 |
| `apm-lab-zipkin-trace.png` | Zipkin 트레이스 상세 — Duration 2.525 s, Total Spans 20. **오른쪽 상세 패널은 잘라냈습니다**(호스트 이름이 그대로 나옵니다) |
| `apm-lab-tempo-trace.png` | Grafana Explore에서 본 Tempo — 왼쪽 TraceQL 결과 목록, 오른쪽 트레이스(2.53 s, 20 spans) |
| `apm-lab-pinpoint-servermap.png` | Pinpoint 서버맵 — USER·pay-api·PostgreSQL·외부기관 네 노드와 호출 수/지연, 산점도, Apdex 0.82 |
| `apm-lab-signoz-signup.png` | SigNoz 첫 화면 — "Create your account". **이 화면을 넘기기 전에는 수집도 되지 않습니다**(§4) |

캡처는 `load-tests/apm-capture.mjs`가 같은 창 크기(1600×1000)로 찍습니다. 손으로 찍으면 창 크기와
조회 구간이 매번 달라져 도구끼리 비교가 안 되고, 메모리 저장소를 쓰는 도구는 보관이 수 분이라
부하 직후가 아니면 빈 화면이 나옵니다. 캡처는 블로그 저장소 `assets/img/posts/`에 두었고, 화면에
API 키·개인정보가 없는지 확인했습니다.

**찍었다가 버린 것 둘.** SkyWalking 트레이스 화면은 해시 라우트가 대시보드로 되돌아가 열지 못했고, 같은
대시보드가 한 번 더 찍혔습니다. 다른 한 장은 위젯이 비어 있는 빈 대시보드(`Please add widgets.`)였습니다.
둘 다 보여 주는 것이 없어 지웠습니다. SkyWalking 트레이스 화면은 **미캡처**로 남습니다.

## 7. 기본 샘플링과 보관

| 도구 | 샘플링 | 보관 |
|---|---|---|
| OTel 에이전트 | 기본 `parentbased_always_on`. 이 실험은 `always_on`으로 고정 | — |
| Jaeger all-in-one | — | **메모리.** 상한 없으면 OOM, `MEMORY_MAX_TRACES=20000`에서는 이 부하에 **수 분** |
| Zipkin | — | **메모리.** Jaeger와 같은 이유로 `MEM_MAX_SPANS=500000` (우리가 설정) |
| Tempo | — | `block_retention: 24h` (우리가 설정) |
| Pinpoint | 기본 `profiler.sampling.counting.sampling-rate=20`(5%). 이 실험은 **1(전량)** 로 고정 | 테이블별 TTL. 트레이스 `TraceV2` 기본 60일 |
| SkyWalking / SigNoz | 미측정 | 미측정 |

## 8. 하니스에서 틀린 것

| # | 틀린 것 | 증상 |
|---|---|---|
| 1 | 프로필만 주고 `docker compose up` | 기본 파일의 모든 서비스(postgres·기관 등)가 함께 떠서 포트 충돌. 서비스 이름을 지정하게 고침 |
| 2 | 포트를 고르고 나서 앞 백엔드를 내림 | 살아 있는 컬렉터가 4317을 쥐고 있어 다음 실행이 4319로 밀리고 앱 설정까지 따라 바뀜 |
| 3 | `pick_port`를 `$(...)`에서 호출 | 예약이 전달되지 않아 4327·4328이 **둘 다 4329**를 받음. 예약 파일로 고침(dev.sh와 같은 장치) |
| 4 | 에이전트 내려받기 진행 메시지를 stdout으로 | 함수의 반환값(경로)에 섞여 `-javaagent:/…/== SkyWalking Java 에이전트 9.7.0 내려받기`가 됨 |
| 5 | 팔별로 몰아서 측정 | §5.2 |
| 6 | 기관 장애 모드 필드 이름을 `mode`로 추측 | 실제 필드는 `approvalMode`. 204가 돌아오지만 **아무것도 바뀌지 않아** 느린 호출이 재현되지 않음 |
| 7 | `apm.sh`를 거치지 않고 `docker compose up`을 직접 호출 | 포트 배정이 돌지 않아 Pinpoint Web이 스크립트가 알려 준 8082가 아니라 기본값 8081에 붙음. **"UI가 안 뜬다"로 15분을 씀** |
| 8 | `.apm/env`를 `set -a; . .apm/env`로 읽음 | Pinpoint의 `JAVA_TOOL_OPTIONS`에는 **공백이 있습니다**. 셸이 쪼개서 `-Dpinpoint.agentId=…: command not found`. dev.sh와 하니스는 한 줄씩 읽어 `env`에 넘기므로 멀쩡합니다 |
| 9 | 조회 조건을 URL에만 넣고 캡처 | Jaeger도 Zipkin도 **조회 버튼을 눌러야** 결과가 나옵니다. Grafana Explore는 익명 Viewer로 아예 열리지 않아 세션이 필요합니다 |

6번이 특히 조용합니다 — 관리 API가 200/204를 돌려주므로 설정이 먹은 것처럼 보이고, 부하는 정상 속도로
끝납니다. "장애를 주입했는데 아무 일도 없었다"는 결과를 **도구가 못 본 것으로 읽기 쉽습니다.** 그래서
지금은 부하를 걸고 나서 클라이언트가 본 지연의 중앙값부터 봅니다. 2.5초 근처가 아니면 주입이 안 먹은
것으로 칩니다.

## 9. 미측정·미착수

- **Pinpoint 오버헤드의 크기**: 방향만 말할 수 있습니다(§5.6). 백엔드를 다른 기계에 두고 다시 재야 합니다.
  이 측정은 **에뮬레이션으로 도는 HBase가 같은 기계에 있는 상태**라 환경이 지배합니다
- **SigNoz**: 수집과 UI 전부. 첫 관리자 계정을 만들면 열립니다 — 계정 생성은 사용자 몫입니다(§4)
- **Datadog**: 미착수. 체험판 계정과 API 키가 필요하고, 키는 사용자가 직접 넣습니다(`DD_API_KEY=… scripts/apm.sh up datadog`)
- **Zipkin과 Jaeger의 3.1% 차이**: 3회로는 가릴 수 없습니다(§5.4). 횟수를 늘린 측정은 하지 않았습니다
- 자체 에이전트(SkyWalking·Pinpoint)를 OTel 에이전트와 **한 묶음에서** 번갈아 돌린 측정
- 메모리 사용량: Jaeger·SkyWalking(부하 중), 백엔드별 수집 한계
- SkyWalking·SigNoz·Pinpoint의 기본 샘플링·보관
- SkyWalking 트레이스 화면, Zipkin 의존성(Dependencies) 화면, Pinpoint 호출 트리(Call Tree) 화면

## 10. 재실행

```bash
docker compose up -d postgres redpanda mock-bank mock-pg
./gradlew :apps:pay-api:bootJar

scripts/apm.sh up jaeger          # 또는 zipkin | tempo | signoz | skywalking | pinpoint | none
                                  # pinpoint 는 테이블 생성에 약 4분 30초가 걸립니다
scripts/dev.sh                    # .apm/env 를 읽어 앱에 넘깁니다

J=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
python3 load-tests/apm-overhead-experiment.py --jar $J --arms none,jaeger,zipkin --runs 3

# 가시성 조건
curl -X POST localhost:8091/mock-pg/admin/behavior -H 'Content-Type: application/json' \
  -d '{"approvalMode":"HANG_BEFORE_PROCESSING","hangForMillis":2500}'
BASE_URL=http://localhost:8099 PAY_RPS=3 READ_RPS=5 DURATION=35s k6 run load-tests/external-pg-load.js

# 화면 캡처 (부하 직후에 돌려야 합니다 — 메모리 저장소는 보관이 수 분입니다)
node load-tests/apm-capture.mjs zipkin --out /tmp/apm-shots
CAPTURE_TZ=Asia/Seoul node load-tests/apm-capture.mjs pinpoint --out /tmp/apm-shots
GRAFANA_USER=<compose 의 값> GRAFANA_PASSWORD=<compose 의 값> \
  node load-tests/apm-capture.mjs tempo --out /tmp/apm-shots
```
