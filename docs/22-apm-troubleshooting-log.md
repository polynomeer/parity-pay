# APM 도구를 띄우면서 막힌 것들 (DOC-22)

> **에이전트 지침**
> - **읽는 시점**: `scripts/apm.sh`로 백엔드를 띄웠는데 화면이 비어 있을 때, 새 APM 도구를 추가할 때.
> - **이 문서가 정하는 것**: 아무것도 정하지 않습니다. 구조의 기준은 [ADR-017](adr/017-swappable-apm-backend.md), 측정값은 [RPT-04](../reports/14-apm-tool-comparison.md)입니다.
> - **강제 규칙**: 여기에는 **실제로 겪은 것만** 적습니다. 추측한 원인은 추측이라고 적고, 틀렸던 진단은 지우지 말고 §6에 남깁니다.

## 1. 이 문서가 무엇인가

APM 백엔드 여섯 개(Jaeger·Zipkin·Tempo·SkyWalking·Pinpoint·SigNoz)를 같은 애플리케이션에 붙여
보면서 막힌 지점과 그 원인을 적은 기록입니다. [RPT-04](../reports/14-apm-tool-comparison.md)가
"무엇이 측정됐나"를 맡고, 이 문서가 **"거기까지 가는 데 무엇이 막았나"**를 맡습니다.

둘을 나눈 이유가 있습니다. 측정 보고서는 숫자가 중심이라 과정이 들어가면 읽기 어렵고, 과정은
숫자보다 재사용 가치가 높습니다. 여기 있는 열아홉 가지 중 **열두 가지는 도구가 아니라 환경과
사람이 만든 것**이고, 그 열두 가지는 다른 도구를 붙일 때도 똑같이 나옵니다.

## 2. "아무것도 안 보인다"는 같은 증상, 다른 원인 다섯

이 실험에서 가장 자주 본 화면은 빈 화면입니다. 그런데 원인이 매번 달랐습니다. **증상으로 원인을
좁힐 수 없다**는 것이 이 표의 요점입니다.

| # | 보이는 것 | 실제 원인 | 어떻게 갈랐나 |
|---|---|---|---|
| 1 | 트레이스 목록이 비어 있음 | 조회 **버튼을 누르지 않음**. Jaeger와 Zipkin은 URL이 조건을 채워도 조회는 버튼이 함 | 버튼을 누르니 나옴 |
| 2 | 1시간 조회가 0건 | Jaeger 메모리 저장소의 **보관이 수 분**. 배경 작업이 링버퍼를 밀어냄 | 방금 넣은 부하만 보임 |
| 3 | 부하를 걸었는데 느린 요청이 없음 | 장애 주입이 **안 먹음**(필드 이름을 `mode`로 추측, 실제는 `approvalMode`). 204가 돌아와서 먹은 것처럼 보임 | 클라이언트가 본 지연 중앙값이 2.5초가 아니었음 |
| 4 | Pinpoint에 "There are no running agents" | **시간대**. 헤드리스 브라우저가 UTC라 서버가 KST로 읽으면 9시간 어긋남 | 같은 순간에 브라우저 창에서는 데이터가 보임 |
| 5 | SigNoz에 트레이스 0건 | **조직이 없어서** ingester가 OTLP 포트를 아예 열지 않음(§3.6) | 호스트에서 포트가 닫혀 있었음 |

3번과 5번이 특히 조용합니다. 둘 다 **모든 신호가 정상**입니다. 관리 API는 204를 돌려주고,
컨테이너는 여섯 개 다 `Up`이고, 헬스체크도 통과합니다.

## 3. 도구별 기록

### 3.1 Jaeger — 기본값이 위험했다

all-in-one의 메모리 저장소에는 상한이 없습니다. 전량 샘플링 부하에서 계속 자라다가 컨테이너가
**두 번 OOM으로 죽었습니다**(`Exited (137)`). `MEMORY_MAX_TRACES=20000`과 `mem_limit: 1g`로
묶었더니 이번에는 **보관이 수 분**으로 줄었습니다. Outbox 발행기·복구 작업·불변조건 지표가 쉬지
않고 스팬 하나짜리 트레이스를 만들어 링버퍼를 밀어내기 때문입니다.

배경 작업이 많은 애플리케이션에서 "메모리 저장소로 일단 띄워 보기"는 생각보다 짧은 창만 줍니다.

### 3.2 Zipkin — 막힌 것 없음, 대신 형식이 다르다

여섯 중 가장 쉬웠습니다. 컨테이너 하나, 저장소는 메모리, 한 번에 떴습니다.

유일한 특이점은 **OTLP를 받지 않는다**는 것입니다. 2012년에 자기 형식으로 시작했고 그 자리를
지키고 있습니다. 그래서 컬렉터의 exporter만 `zipkin`으로 바꿨고(`collector-zipkin.yaml`),
애플리케이션은 한 줄도 바뀌지 않았습니다. ADR-017이 노린 성질이 여기서 확인됩니다 —
**형식을 맞추는 일이 컬렉터 안에서 끝납니다.**

### 3.3 Tempo — 화면이 없다

Tempo에는 UI가 없습니다. Grafana의 Explore에서 TraceQL로 봅니다. 컨테이너 수를 셀 때
**"그 백엔드를 볼 도구"가 빠지기 쉽습니다.**

그리고 Grafana Explore는 익명 Viewer로 열리지 않고 로그인 화면으로 되돌려 보냅니다. Basic 인증
헤더도 UI 경로에서는 무시되므로 캡처 스크립트가 **세션을 한 번 만들어야** 합니다.

### 3.4 SkyWalking — 세 번 막혔다

| 시도 | 결과 |
|---|---|
| OAP 10.4 + `SW_STORAGE=h2` | `no provider found for module storage`로 종료. 10.x 이미지의 `oap-libs`에는 `storage-banyandb-plugin`만 있습니다 — **메모리 저장소가 없습니다** |
| OAP 10.4 + BanyanDB `0.11.1`(최신) | `Incompatible BanyanDB server API version: 0.11. But accepted versions: 0.10`으로 종료 |
| OAP 10.4 + BanyanDB `0.10.3` | 앞 버전이 쓴 볼륨을 읽지 못해 종료(`unknown field "created…"`). 볼륨을 지우고서야 기동 |

저장소를 따로 두는 도구는 **그 둘의 버전까지 맞춰야** 합니다. 그리고 한 번 더 중요한 것은,
버전을 내릴 때 **앞 버전이 쓴 볼륨을 지워야 한다**는 점입니다.

### 3.5 Pinpoint — 다섯 번 막히고 두 번 더 막혔다

가장 오래 걸린 도구입니다. 컨테이너 다섯 개(HBase·ZooKeeper·MySQL·Redis·Collector·Web 중
Web 포함 여섯)가 필요하고, 막힌 자리가 전부 서로 다른 층에 있었습니다.

**① `depends_on`은 "컨테이너가 떴다"까지만 본다.** HBase가 테이블을 만드는 동안 collector와
web이 `NoNode for /hbase/hbaseid`로 죽습니다. 공식 compose도 같은 이유로 `restart`에 맡깁니다.
→ `restart: unless-stopped`.

**② HBase 이미지에 ZooKeeper 주소가 박혀 있다.** 이미지 안 `hbase-site.xml`에
`<value>zoo1,zoo2,zoo3</value>`가 들어 있습니다. 컨테이너를 다른 이름으로 띄우면 HBase가
`zoo1: Name or service not known`으로 **테이블조차 만들지 못합니다.**
→ 컨테이너 하나에 `zoo1`·`zoo2`·`zoo3` 별칭 세 개.

**③ HBase 접속과 클러스터 조정이 한 변수를 공유한다.** collector 설정이
`hbase.client.host=${pinpoint.zookeeper.address}`입니다. 별도 ZooKeeper를 가리키면 거기에는
HBase가 자기를 등록하지 않았으므로 같은 `NoNode`로 죽습니다.
→ 둘 다 `zoo1`.

**④ 공식 예시의 MySQL 비밀번호가 `admin/admin`.** 이 저장소는 비밀값에 기본값을 두지 않습니다
(ADR-011). → `apm.sh`가 설치할 때 한 번 만들어 `.apm/`에 두고, 없으면 compose가 뜨지 않습니다.

**⑤ `pinpoint-hbase` 이미지가 amd64 단일 아키텍처.** `docker manifest inspect` 기준으로
collector·web·Zipkin·SkyWalking OAP는 `linux/amd64`와 `linux/arm64`를 모두 내는데
**HBase 이미지만 단일**입니다. Apple Silicon에서는 에뮬레이션으로 돌고, 그 속도로
**리전 1,900개가 넘는 스키마**를 만들다가 ZooKeeper 세션이 만료되어 마스터가 스스로 죽습니다
(`KeeperErrorCode = Session expired for /hbase/master`). 테이블 22개 중 4~6개만 만들어진 채
멈추고, Pinpoint Web은 `TableNotFoundException: Application`을 돌려줍니다.

리전 수는 스키마 파일에 적혀 있습니다. 테이블 7개가 `NUMREGIONS => 256`이고 9개에 명시적
`SPLITS`가 있습니다. 노드가 여럿이면 그래야 쓰기가 흩어지지만, **한 노드짜리 실험 스택에서는
흩어질 곳이 없습니다.**
→ `NUMREGIONS => 4`, 명시적 `SPLITS` 제거. 테이블 이름·컬럼 패밀리·TTL은 원본 그대로
(`deploy/observability/pinpoint/hbase-create.hbase`).

**⑥ 세션 시간은 한쪽만 올리면 조용히 깎인다.** ⑤를 고치려고 `hbase-site.xml`의
`zookeeper.session.timeout`을 10분으로 올렸는데 그대로 멈췄습니다. **세션 시간의 상한은
ZooKeeper 서버가 쥐고 있고**, 기본값이 `20 × tickTime`입니다. 그리고 `zookeeper:3.4.13`
이미지에는 `ZOO_MAX_SESSION_TIMEOUT` 같은 변수가 **없습니다** — 지원 변수는 `ZOO_TICK_TIME`,
`ZOO_INIT_LIMIT`, `ZOO_SYNC_LIMIT` 등입니다(`docker-entrypoint.sh`에서 확인).
→ `ZOO_TICK_TIME: 15000`(상한 300초) + `zookeeper.session.timeout: 300000`.

⑤와 ⑥을 **함께** 고치자 테이블 22개가 약 4분 30초에 전부 만들어졌고, Pinpoint Web이
`/api/applications`에 `pay-api`를 돌려줬습니다.

**⑦ 이미지의 초기화 스크립트가 스키마 파일을 `sed -i`로 고친다.** 그래서 그 자리에 바인드
마운트하면 안 됩니다(마운트된 단일 파일은 rename이 막힙니다). → 다른 자리에 두고 entrypoint에서
복사한 뒤 원래 초기화 스크립트를 부릅니다.

### 3.6 SigNoz — 계정을 만들기 전에는 수집 자체가 안 된다

이것이 이 실험에서 가장 늦게 밝혀진 원인입니다.

처음에는 **ClickHouse 분산 DDL 스키마 마이그레이션이 10분 넘게 끝나지 않는 것**을 원인으로
적었습니다. 컨테이너를 recreate 했더니 클러스터 메타데이터에 죽은 복제본 호스트가 남아
(`Cannot resolve host (a39b5c2f3c57)`) 마이그레이션이 영원히 끝나지 않은 것은 사실입니다.
그래서 **볼륨까지 지우고 한 번에 올려야 한다**는 것도 사실입니다.

그런데 이번에는 볼륨을 지우고 한 번에 올렸고, 마이그레이터가 **2분 안에 `Exited (0)`**으로
끝났으며 `signoz_traces`에 테이블 36개가 생겼습니다. 그런데도 트레이스는 0건이었습니다.

원인은 다른 곳에 있었습니다.

```text
우리 컬렉터  --(OTLP)-->  signoz-ingester:4317   ← 아무도 듣고 있지 않음
                               ▲
                               │ OpAMP 로 파이프라인 설정을 받아야 포트를 연다
                          signoz-signoz-0  ← "failed to find or create agent"
                               ▲
                               │ agent 레코드는 조직(organization)에 속한다
                          organizations = 0   ← 첫 관리자 계정을 만들어야 생긴다
```

확인한 것은 넷입니다.

1. 우리 컬렉터 로그: `Exporting failed ... error reading server preface: EOF`
   (처음 띄웠을 때는 `addrConn.createTransport failed to connect`)
2. ingester 컨테이너가 **실제로 듣고 있는 포트**: `8888`(자기 지표), `13133`(헬스체크), `1777`(pprof).
   **4317도 4318도 없습니다.**
3. 그런데도 `docker port`는 `4317 -> 4327`을 보여 주고 호스트에서 `nc -z localhost 4327`은 **성공합니다**
4. 메타스토어(PostgreSQL)에 `organizations = 0`, `users = 0`, 그리고 SigNoz 서버 로그에
   `failed to find or create agent`

**3번은 함정입니다.** 공개한 포트는 Docker의 userland 프록시가 호스트 쪽에서 붙잡고 있어서, 컨테이너
안에서 아무도 듣지 않아도 **TCP 연결은 받아들입니다.** 그래서 `nc -z`는 "열림"이라 답하고, 실제
gRPC 핸드셰이크에 가서야 `error reading server preface: EOF`로 끊깁니다. 포트가 살아 있는지 보려면
`nc`가 아니라 **컨테이너 안의 `/proc/net/tcp`**를 봐야 합니다.

```bash
# 거짓말하는 확인
nc -z localhost 4327                      # succeeded  <- Docker 프록시

# 사실대로 말하는 확인
docker exec <ingester> sh -c 'cat /proc/net/tcp /proc/net/tcp6' \
  | tail -n +2 | awk '{split($2,a,":"); print a[2]}' | sort -u   # 16진수 포트
```

즉 **SigNoz는 첫 관리자 계정이 만들어지기 전까지 아무것도 수집하지 않습니다.** 화면을 안 보는
것이 아니라 수집 경로 자체가 열리지 않습니다. 설치만 자동화하고 계정 생성을 사람에게 맡기는
파이프라인이라면 이 지점에서 멈춥니다.

이 저장소는 계정 생성을 사람의 몫으로 둡니다. 다음 한 줄이 남은 전부입니다.

```text
http://localhost:<PARITYPAY_SIGNOZ_PORT> 를 열고 첫 관리자 계정을 만듭니다.
그 순간 조직이 생기고, ingester 가 OTLP 포트를 열며, 그때부터 트레이스가 들어옵니다.
```

**확인했습니다(2026-10-09).** 사용자가 계정을 만든 뒤 같은 명령으로 다시 보면 이렇게 바뀝니다.

| | 계정 전 | 계정 후 |
|---|---|---|
| `organizations` / `users` | 0 / 0 | 1 / 1 |
| ingester 가 듣는 포트(컨테이너 안) | `8888`·`13133`·`1777` | 여기에 **`4317`·`4318` 추가** |
| 컬렉터 내보내기 실패(45초) | 35건 | **0건** |
| ClickHouse 스팬 | 0 | **7,902** |

같은 부하에서 `POST /api/v1/payments` p50 **2,608 ms**, 자식 외부 호출 `POST` p50 **2,524 ms**로
다른 도구와 같은 값이 나왔습니다. 원인은 조직이 맞았습니다.

## 4. 하니스와 측정에서 틀린 것

| # | 틀린 것 | 증상 |
|---|---|---|
| 1 | 프로필만 주고 `docker compose up` | 기본 파일의 모든 서비스(postgres·기관 등)가 함께 떠서 포트 충돌 |
| 2 | 포트를 고르고 나서 앞 백엔드를 내림 | 살아 있는 컬렉터가 4317을 쥐고 있어 다음 실행이 4319로 밀리고 앱 설정까지 따라 바뀜 |
| 3 | `pick_port`를 `$(...)`에서 호출 | 예약이 전달되지 않아 4327·4328이 **둘 다 4329**를 받음 |
| 4 | 에이전트 내려받기 진행 메시지를 stdout으로 | 함수의 반환값(경로)에 섞여 `-javaagent:/…/== SkyWalking Java 에이전트 9.7.0 내려받기`가 됨 |
| 5 | 팔별로 몰아서 측정 | 기계 상태 변화가 전부 뒤쪽 팔의 성과로 보임. RPT-04 §5.2 |
| 6 | 기관 장애 모드 필드 이름을 추측 | §2의 3번 |
| 7 | `apm.sh`를 거치지 않고 `docker compose up`을 직접 호출 | 포트 배정이 돌지 않아 Web이 스크립트가 알려 준 포트가 아니라 기본 포트에 붙음. **두 번 반복했습니다** |
| 8 | `.apm/env`를 `set -a; . .apm/env`로 읽음 | Pinpoint의 `JAVA_TOOL_OPTIONS`에는 **공백이 있습니다**. 셸이 쪼개서 `-Dpinpoint.agentId=…: command not found`. `dev.sh`와 하니스는 한 줄씩 읽어 `env`에 넘기므로 멀쩡합니다 |

7번은 같은 실수를 두 번 했다는 점에서 기록할 값이 있습니다. 스크립트가 포트를 정하고 알려 주는데,
급할 때 스크립트를 건너뛰면 **스크립트가 알려 준 주소와 실제 주소가 달라집니다.**

## 5. 캡처에서 틀린 것

- **조회 조건을 URL에만 넣었다.** Jaeger도 Zipkin도 조건은 URL이 채우지만 조회는 버튼이 합니다.
- **Grafana Explore는 익명으로 열리지 않는다.** Basic 인증 헤더는 UI 경로에서 무시되므로
  세션을 만들어야 합니다.
- **Pinpoint는 브라우저 시간대로 조회 구간을 만든다.** 헤드리스 브라우저(UTC)로 찍으면
  한국에서는 9시간 어긋난 빈 화면이 나옵니다. 조회 구간을 **직접 계산해서 URL에 넣습니다.**
- **쓸모없는 캡처를 두 장 찍었다.** 위젯이 비어 있는 대시보드와, 트레이스 화면인 줄 알았던
  같은 대시보드입니다. 둘 다 지웠습니다.
- **공개용 캡처에 호스트 이름이 들어갔다.** Zipkin 상세의 오른쪽 태그 패널에 `host.name`이
  나옵니다. 그 패널을 잘라냈습니다.

**SkyWalking의 트레이스 화면은 주소가 없습니다.** 왼쪽 메뉴에도 없고 `/General-Service/Trace` 같은
주소로 가면 404입니다. 서비스 대시보드 **아래쪽 탭**(`Service · Topology · Trace · Log`)이고, 그 탭은
화면 밖에 있어 **스크롤해서 그려지기 전에는 DOM에도 없습니다.** 처음에 해시 라우트를 시도한 것이
틀렸던 이유입니다. 그리고 `/General-Service/Services`는 **직접 열면 404**이고, `/`에서 SPA가 부팅한
뒤에만 유효한 클라이언트 라우트입니다.

**그 화면을 끝내 캡처하지 못한 이유는 또 다릅니다.** 스크립트로 같은 경로를 밟는 동안 OAP의 대시보드
템플릿이 사라져(대시보드 목록이 `No Data`) 서비스 화면 자체가 열리지 않게 됐고, UI 컨테이너를 다시
띄워도 돌아오지 않았습니다. 화면에서 무엇을 보여 주는지는 눈으로 확인해 RPT-04 §6에 적었습니다.

**Zipkin 의존성 화면이 비어 있는 것은 고장이 아닙니다.** `/api/v2/dependencies`가 `[]`를 돌려줍니다.
의존성 그래프는 **서비스 둘 이상** 사이의 호출로 그려지는데 이 랩은 `pay-api` 하나만 계측합니다.
도구를 의심하기 전에 **내가 몇 개를 계측했는지**를 먼저 봐야 합니다.

그래서 캡처를 `load-tests/apm-capture.mjs`로 옮겼습니다. 손으로 찍으면 창 크기와 조회 구간이
매번 달라져 도구끼리 비교가 안 되고, 메모리 저장소를 쓰는 도구는 부하 직후가 아니면 빈 화면입니다.

## 6. 내가 틀리게 진단한 것

이 절이 이 문서에서 가장 쓸모 있는 부분입니다. **원인을 잘못 짚고 한참 간 경우**를 지우지 않고
남깁니다.

**① "Pinpoint가 Spring Boot 4 / Tomcat 11을 계측하지 못한다"** — 틀렸습니다.
수집기 로그의 `grpcSpanReceiver CurrentTransport:1, CurrentGrpcStream:0`을 보고 "스팬이 하나도
안 온다"로 읽었고, 우리 앱이 Tomcat 11이라는 사실과 묶어 "플러그인이 지원하지 않는다"로
결론 내렸습니다. 플러그인이 후킹하는 `StandardHostValve`가 Tomcat 11에도 있는지까지 확인했는데도
그 방향을 계속 팠습니다.

갈라 준 것은 두 가지입니다. 첫째, 에이전트 로그에
`SpanBatchGrpcDataSender -- ConnectivityState changed before:CONNECTING, change:READY`가 있었습니다
— 스팬 전송 채널은 **보낼 것이 생겨야** 연결됩니다. 둘째, 조회 구간을 바로잡고 UI를 열자
Apdex 0.82, Success 1,527이 나왔습니다. `CurrentGrpcStream`은 **그 순간 열려 있는 스트림 수**이고,
배치 전송은 열고 닫습니다. 0이라는 것은 실패가 아니라 "지금은 안 보내는 중"입니다.

교훈: **계수기 하나로 "없다"를 결론 내리지 않습니다.** 반대편(보내는 쪽) 로그를 함께 봐야 합니다.

**② "SigNoz는 스키마 마이그레이션 때문에 트레이스를 못 받는다"** — 절반만 맞았습니다.
마이그레이션이 끝나지 않은 것은 사실이고 recreate가 그것을 영구화한 것도 사실이지만,
마이그레이션을 끝내 놓고도 트레이스는 0건이었습니다. 진짜 문 턱은 §3.6의 조직이었습니다.

교훈: **눈에 띄는 고장 하나를 고쳤다고 증상이 사라지는지 확인하지 않으면**, 그것이 원인이었다고
믿게 됩니다.

**③ "Pinpoint의 실패는 Pinpoint의 문제"** — 아닙니다. 다섯 가지 중 ②③⑤⑥은 전부
**이 기계(Apple Silicon)와 한 노드짜리 구성** 때문입니다. x86 호스트에서는 ⑤가 아예 없고,
노드가 여럿이면 ⑥도 만날 일이 적습니다. 도구의 성질과 환경의 성질을 섞어 적으면 다음 사람이
잘못된 결론을 가져갑니다.

## 7. 다음 사람이 쓸 체크리스트

새 APM 백엔드를 붙일 때 이 순서로 확인하면 위의 대부분을 건너뜁니다.

1. **이미지의 아키텍처를 먼저 본다.** `docker manifest inspect <image>` — 단일 아키텍처면
   에뮬레이션이고, 저장소 계열이면 초기화에서 막힐 가능성이 큽니다.
2. **스택을 `scripts/apm.sh`로만 띄운다.** 포트 배정과 `.apm/env` 작성이 거기서 끝납니다.
3. **백엔드가 포트를 실제로 듣고 있는지 컨테이너 안에서 확인한다.** 컨테이너가 `Up`인 것과 포트가
   열린 것은 다르고, **호스트에서 `nc -z`로 보는 것도 믿을 수 없습니다**(§3.6). `/proc/net/tcp`를 봅니다.
4. **부하를 걸기 전에 장애 주입이 먹었는지 확인한다.** 클라이언트가 본 지연의 중앙값부터 봅니다.
5. **조회 구간과 시간대를 의심한다.** 빈 화면의 절반은 여기서 나옵니다.
6. **"없다"를 결론 내리기 전에 보내는 쪽 로그를 본다.**

## 8. 관련 문서

- [ADR-017](adr/017-swappable-apm-backend.md) — 왜 백엔드만 바꿔 끼우는 구조인가
- [RPT-04](../reports/14-apm-tool-comparison.md) — 무엇을 쟀고 무엇이 나왔나
- [ADR-013](adr/013-local-dev-stack-script.md) — 포트 우회 규칙(여기 ②③과 같은 장치)
- [ADR-011](adr/011-deployment-shape.md) — 비밀값에 기본값을 두지 않는 규칙(§3.5 ④)
