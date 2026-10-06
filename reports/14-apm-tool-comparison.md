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
  외부 호출 `POST` 2.506초(**99.4%**)가 바로 보입니다.
- **못 한 것**: SigNoz 트레이스 수신(스키마 마이그레이션이 끝나지 않음)과 UI(계정 필요), Pinpoint(미착수),
  Datadog(체험판 계정 필요), Tempo 화면 캡처, SkyWalking 트레이스 화면 캡처.

## 2. 환경

| 항목 | 값 |
|---|---|
| 커밋 | `3e4a547` (측정 시점의 `main`) |
| 측정 일시 | 2026-10-05 ~ 2026-10-06 |
| 기계 | Apple M1 Max, 32 GB, macOS 26.6.2, Docker Desktop 28.0.4 (**Docker 할당 7.7 GiB**) |
| 애플리케이션 | pay-api (Java 21, `local` 프로필), 호스트 프로세스. 의존: postgres 17·Redpanda·mock-bank·mock-pg(컨테이너) |
| 부하 | k6 v1.0.0. 오버헤드는 `payment-baseline.js`(VU 20, P-001과 같은 조건), 가시성은 `external-pg-load.js` |
| 계측 | OpenTelemetry Java agent **2.12.0**, SkyWalking Java agent **9.7.0**, OTel Collector **0.119.0** |
| 백엔드 | Jaeger all-in-one **1.65.0**, Grafana Tempo **2.7.0**, SigNoz(foundryctl **v0.3.0**이 생성), SkyWalking OAP·UI **10.4.0-java21** + BanyanDB **0.10.3** |
| 잡음 | **이 기계에는 다른 프로젝트의 컨테이너가 20여 개 떠 있습니다.** 절대값이 아니라 같은 실행 안의 상대 비교만 씁니다 |

> 측정 중 이 기계의 Docker Hub 이미지 내려받기가 매우 느렸습니다 — SkyWalking OAP+UI 약 19분,
> BanyanDB 0.10.3은 8분 넘게 진척이 없었고, SigNoz 이미지 중 하나는 `lease does not exist`로 실패해
> `docker builder prune -af`(16 GB 회수) 뒤에 받았습니다. 소요 시간 수치에는 이 조건이 섞여 있습니다.

## 3. 방법

```text
                                   ┌─ collector-jaeger.yaml  → Jaeger
pay-api ──(OTLP 4317)──▶ Collector ─┼─ collector-tempo.yaml   → Tempo
   ▲ -javaagent (OTel)              └─ collector-signoz.yaml  → SigNoz
   └── 자체 에이전트 계열은 이 자리를 바꿔 끼움 (SkyWalking)
```

- 전환은 `scripts/apm.sh up <name>`입니다. 그것이 `.apm/env`를 적고, 앱을 띄우는 쪽이 그 파일을 읽습니다.
- **OTLP 계열끼리의 전환에는 앱 재시작이 필요 없습니다**(컬렉터 설정만 바뀜). 자체 에이전트 계열은 필요합니다.
- 오버헤드는 `load-tests/apm-overhead-experiment.py`가 잽니다. 팔마다 앱을 새로 띄우고 k6를 돌립니다.
- 가시성은 Mock PG를 `HANG_BEFORE_PROCESSING` **2,500 ms**로 두고(읽기 타임아웃 3초 아래 — 타임아웃이 아니라
  "느린 성공"을 만들기 위해서입니다) `external-pg-load.js`로 부하를 겁니다.

## 4. 설치 비용 — 실측

| 도구 | 컨테이너 | 저장소 | 첫 기동까지 막힌 횟수 | 메모리 |
|---|---:|---|---:|---|
| Jaeger (기준선) | 1 (+컬렉터) | 메모리 | 0 | 미측정 |
| Grafana Tempo | 1 (+컬렉터) | 로컬 디스크 | 0 | 미측정 |
| SigNoz | **6** | ClickHouse + Keeper + PostgreSQL | 1 (아래) | **약 970 MiB** (ClickHouse 737 MiB) |
| SkyWalking | **3** | BanyanDB(필수) | **3** (아래) | 미측정 |
| Pinpoint | — | HBase | — | **미착수** |
| Datadog | — | SaaS | — | **미착수**(체험판 계정 필요) |

**SigNoz에서 막힌 것.** 2025년에 docker-compose 설치가 폐기되고 전용 설치기(`foundryctl`)로 옮겼습니다.
공식 안내는 `curl … | bash`이고, 이 실험은 같은 일을 단계로 나눠 했습니다 — 공식 GitHub 릴리스의 tarball과
checksums를 받아 **sha256을 검증한 뒤** 설치합니다(`scripts/apm.sh`). 생성된 compose는 4317·4318·8080을
요구해 우리 컬렉터와 부딪히므로 호스트 포트를 다시 매핑합니다. 띄운 뒤에는 **ClickHouse 분산 DDL 스키마
마이그레이션이 10분 넘게 끝나지 않았고**, 그동안 ingester가 OTLP를 받지 않아 **트레이스가 한 건도 들어오지
않았습니다**. 또 컨테이너를 recreate 했더니 클러스터 메타데이터에 죽은 복제본 호스트가 남아
(`Cannot resolve host (a39b5c2f3c57)`) 마이그레이션이 영원히 끝나지 않는 상태가 됐습니다 — 볼륨까지 지우고
한 번에 올려야 했습니다.

**SkyWalking에서 막힌 것 셋.**

| 시도 | 결과 |
|---|---|
| OAP 10.4 + `SW_STORAGE=h2` | `no provider found for module storage`로 **종료**. 10.x 이미지의 `oap-libs`에는 `storage-banyandb-plugin`만 있습니다 — **메모리 저장소가 없습니다** |
| OAP 10.4 + BanyanDB `0.11.1`(최신) | `Incompatible BanyanDB server API version: 0.11. But accepted versions: 0.10`으로 **종료** |
| OAP 10.4 + BanyanDB `0.10.3` | 앞 버전이 쓴 볼륨을 읽지 못해 **종료**(`unknown field "created…"`). 볼륨을 지우고서야 기동 |

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

## 6. 같은 장애에서 무엇이 보이는가 — 실측

Mock PG가 승인 전에 2.5초를 붙잡는 조건에서 결제 3 rps + 잔액 조회 5 rps를 걸었습니다. 클라이언트가 본
결제 지연은 중앙값 2.52초였습니다.

| 도구 | 원인까지 | 화면에서 보이는 것 |
|---|---|---|
| **Jaeger** | **2단계** (목록 → 상세) | 루트 `POST /api/v1/payments` **2.52 s**, 자식 `POST`(외부 호출) **2.506 s** — 전체의 99.4%. DB 스팬은 µs 단위. 트레이스당 20~21 스팬 |
| **SkyWalking** | 1단계 (대시보드) | 서비스 평균 응답시간·Apdex·엔드포인트별 부하/지연이 **대시보드 첫 화면**에 바로 나옵니다. 다만 "어느 호출이 느린가"는 엔드포인트 단위이고, 스팬 수준은 트레이스 화면으로 들어가야 합니다 |
| **Tempo** | 미측정 | 트레이스 수신은 API로 확인했지만(5건) 화면 캡처는 하지 못했습니다 |
| SigNoz | — | 트레이스가 들어오지 않았습니다(§4) |

**부수 효과 하나.** SkyWalking은 자체 에이전트가 의존성까지 토폴로지에 올립니다 — `listServices`에
`pay-api` 외에 `localhost:5435`(PostgreSQL)와 `localhost:9092`(Kafka)가 서비스로 잡혔습니다. OTel 에이전트 +
Jaeger 조합에서는 서비스가 `pay-api` 하나입니다. **같은 요청을 보는 방식 자체가 다릅니다.**

### 화면 캡처

| 파일 | 무엇 |
|---|---|
| `apm-lab-jaeger-search.png` | 트레이스 목록 — 2.52~2.53초가 20건, 산점도 |
| `apm-lab-jaeger-trace.png` | 트레이스 상세 — 외부 호출 스팬이 전체 구간을 차지 |
| `apm-lab-skywalking-services.png`·`-topology.png` | SkyWalking 대시보드(서비스·엔드포인트 지표) |
| `apm-lab-skywalking-trace.png` | **트레이스 화면이 아닙니다** — 해시 라우트가 대시보드로 되돌아가 캡처하지 못했고, 같은 대시보드가 찍혔습니다 |

캡처는 블로그 저장소 `assets/img/posts/`에 두었습니다. 화면에 API 키·개인정보가 없는지 확인했습니다.

## 7. 기본 샘플링과 보관

| 도구 | 샘플링 | 보관 |
|---|---|---|
| OTel 에이전트 | 기본 `parentbased_always_on`. 이 실험은 `always_on`으로 고정 | — |
| Jaeger all-in-one | — | **메모리.** 상한 없으면 OOM, `MEMORY_MAX_TRACES=20000`에서는 이 부하에 **수 분** |
| Tempo | — | `block_retention: 24h` (우리가 설정) |
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

6번이 특히 조용합니다 — 관리 API가 200/204를 돌려주므로 설정이 먹은 것처럼 보이고, 부하는 정상 속도로
끝납니다. "장애를 주입했는데 아무 일도 없었다"는 결과를 **도구가 못 본 것으로 읽기 쉽습니다.**

## 9. 미측정·미착수

- **SigNoz**: 트레이스 수신(스키마 마이그레이션 미완료), UI 전부(관리자 계정 생성 필요 — 사용자 지시로 보류)
- **Pinpoint**: 미착수. HBase가 필요하고 이 기계의 이미지 내려받기 속도로는 세션 안에 끝낼 수 없었습니다
- **Datadog**: 미착수. 체험판 계정과 API 키가 필요하고, 키는 사용자가 직접 넣습니다(`DD_API_KEY=… scripts/apm.sh up datadog`)
- 세 팔(none·OTel·SkyWalking)을 **한 묶음에서** 번갈아 돌린 측정
- 메모리 사용량: Jaeger·Tempo·SkyWalking
- SkyWalking·SigNoz의 기본 샘플링·보관
- Tempo 화면, SkyWalking 트레이스 화면

## 10. 재실행

```bash
docker compose up -d postgres redpanda mock-bank mock-pg
./gradlew :apps:pay-api:bootJar

scripts/apm.sh up jaeger          # 또는 tempo | signoz | skywalking | none
scripts/dev.sh                    # .apm/env 를 읽어 앱에 넘깁니다

J=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
python3 load-tests/apm-overhead-experiment.py --jar $J --arms none,jaeger --runs 3

# 가시성 조건
curl -X POST localhost:8091/mock-pg/admin/behavior -H 'Content-Type: application/json' \
  -d '{"approvalMode":"HANG_BEFORE_PROCESSING","hangForMillis":2500}'
BASE_URL=http://localhost:8099 PAY_RPS=3 READ_RPS=5 DURATION=35s k6 run load-tests/external-pg-load.js
```
