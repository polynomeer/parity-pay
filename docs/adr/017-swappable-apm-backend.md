# ADR-017 APM 백엔드를 설정 하나로 바꿔 끼움 (앱은 도구를 모름)

- Status: Accepted
- Decided: 2026-10-05
- Date: 2026-10-05

> **에이전트 영향**: 애플리케이션 코드에 **어떤 APM 벤더의 SDK도 넣지 않습니다.** 계측은 OpenTelemetry
> 자동 계측 Java 에이전트가 하고, 트레이스는 언제나 OTLP 한 자리(컬렉터)로만 보냅니다. 백엔드를 바꾸는
> 것은 `scripts/apm.sh up <name>` 한 줄이며, 바뀌는 파일은 컬렉터 설정
> (`deploy/observability/otel/collector-<name>.yaml`) 하나입니다. 자체 에이전트만 받는 도구는
> `JAVA_TOOL_OPTIONS`의 `-javaagent`를 바꿔 끼웁니다 — **두 에이전트를 동시에 달지 않습니다.**

## Context

APM 도구를 종류별로 붙여 보고 같은 장애에서 무엇이 보이는지 비교하려 합니다(측정은 reports/14).
비교에는 조건이 하나 있습니다 — **도구를 바꿀 때 측정 대상이 같아야 합니다.** 도구마다 코드를 고치면
무엇을 비교한 것인지 알 수 없습니다.

제약도 있습니다.

- 이 저장소는 이미 Spring Boot의 OTLP 내보내기로 Jaeger에 트레이스를 보냅니다(`management.otlp.tracing`).
- 비교 대상에는 OTLP를 받는 도구(Jaeger·Tempo·SigNoz·Datadog Agent)와 **자체 Java 에이전트만 받는
  도구**(SkyWalking·Pinpoint)가 섞여 있습니다. 후자는 OTLP로 받을 수 없습니다.
- 이 호스트에는 다른 프로젝트의 관측 도구가 떠 있습니다. 실제로 `16686`과 `4318`이 다른 Jaeger에게 잡혀
  있었고, 포트를 고정하면 실험이 시작조차 되지 않습니다(ADR-013과 같은 문제).

## Decision

**앱 → OTel 자동 계측 에이전트 → OTel Collector → 백엔드.** 컬렉터를 반드시 사이에 둡니다.

```text
                                   ┌─ collector-jaeger.yaml  → Jaeger
pay-api ──(OTLP 4317)──▶ Collector ─┼─ collector-tempo.yaml   → Grafana Tempo
   ▲ -javaagent (OTel)              ├─ collector-signoz.yaml  → SigNoz
   │                                └─ collector-datadog.yaml → Datadog Agent
   └── 자체 에이전트 도구는 이 자리의 -javaagent 를 바꿔 끼움 (SkyWalking·Pinpoint)
```

- **벤더 SDK를 코드에 넣지 않습니다.** 계측은 에이전트가 하고, 애플리케이션은 자기가 어디로 보내는지도
  모릅니다 — 환경변수에 적힌 한 자리로만 보냅니다.
- **OTLP 계열끼리의 전환은 컬렉터 설정 파일 하나입니다.** 앱을 다시 띄우지 않아도 됩니다(검증에서 확인).
- **자체 에이전트 계열은 `-javaagent`를 바꿉니다.** 그때 OTel 에이전트는 끕니다. 둘을 같이 달면 같은
  호출이 두 번 계측되어 오버헤드 비교가 무의미해집니다.
- **Spring의 OTLP 내보내기는 APM 실험 동안 끕니다**(`MANAGEMENT_TRACING_ENABLED=false`). 에이전트가 같은
  일을 하므로 켜 두면 트레이스가 두 벌 생깁니다.
- **에이전트 jar는 저장소에 담지 않습니다.** `scripts/apm.sh agents`가 공식 배포처에서 내려받아
  `.apm/agents/`에 두고, 그 디렉터리는 `.gitignore`에 있습니다.
- **포트는 비어 있는 것을 찾습니다**(ADR-013과 같은 규칙). 고른 값은 `.apm/env`에 적히고 `scripts/dev.sh`가
  읽어 앱에 넘깁니다. 그래서 전환은 `apm.sh up <name>` → `dev.sh` 두 줄입니다.
- **`apm.sh`는 자기 서비스만 띄우고 내립니다.** 프로필만 주고 `docker compose up`을 하면 기본 파일의 모든
  서비스(postgres·기관 대역 등)가 함께 뜹니다. 그 스택은 `dev.sh`의 것입니다.

## Alternatives

- **앱에 벤더 SDK를 직접 넣음.** 도구마다 코드가 달라지므로 "같은 대상"을 비교할 수 없습니다. 도구를
  떼어낼 때 코드가 남는 것도 문제입니다. 뺍니다.
- **APM별 브랜치.** 비교할 때마다 브랜치를 옮겨야 하고, 측정 대상 커밋이 달라집니다. 뺍니다.
- **컬렉터 없이 앱이 백엔드로 직접 OTLP.** 지금 구조입니다(앱 → Jaeger). 전환할 때 **앱 설정**을 바꿔야
  하고, 백엔드가 OTLP의 어느 방언을 받는지에 앱이 묶입니다. 컬렉터를 두면 그 차이가 앱 밖에 남습니다.
- **에이전트 jar를 저장소에 커밋.** 크고(수십 MB), 버전이 올라가고, 라이선스가 섞입니다. 뺍니다.

## Consequences

- 컨테이너가 하나 늘어납니다(컬렉터). 실험이 아닌 기본 스택은 그대로 앱 → Jaeger 직결입니다 — 실험
  구조가 평소 개발 경로를 바꾸지 않습니다.
- **자체 에이전트 도구는 "설정 하나"로 끝나지 않습니다.** `-javaagent`가 바뀌므로 앱을 다시 띄워야 하고,
  그 사실 자체가 비교 결과의 일부입니다(설치 난이도).
- OTel 에이전트가 계측하는 범위가 비교의 기준선이 됩니다. SkyWalking·Pinpoint는 자기 에이전트가 계측하므로
  **스팬의 모양이 다릅니다.** 같은 요청에 대해 도구가 보여 주는 것이 다른 것은 결과이지 오류가 아닙니다.
- Grafana에 Tempo 데이터소스를 미리 넣어 두었습니다. Tempo 프로필이 꺼져 있으면 그 데이터소스만 오류를
  내고 나머지 화면은 그대로입니다.
- `.apm/`은 커밋되지 않으므로, 다른 사람이 같은 실험을 하려면 `apm.sh agents`가 다시 내려받습니다.

## Validation

- `apm.sh up jaeger` 후 앱을 띄우면 트레이스가 컬렉터를 지나 Jaeger에 들어온다
- `apm.sh up tempo`로 바꾸면 **앱을 다시 띄우지 않고도** 같은 트레이스가 Tempo에 들어온다
- `apm.sh status`가 어떤 APM이 켜져 있는지와 앱에 넘기는 설정을 보여 준다
- `apm.sh`가 `dev.sh`의 컨테이너를 내리지 않는다
- 포트가 잡혀 있으면 비어 있는 포트로 옮긴다

## Outcome — 2026-10-05 (측정은 reports/14)

| 항목 | 결과 |
|---|---|
| Jaeger 경로 | `apm.sh up jaeger` → 앱(OTel 에이전트 2.12.0) → 컬렉터 0.119.0 → Jaeger. `/api/services`에 `pay-api` 등록, 트레이스 조회됨 |
| Tempo 경로 | `apm.sh up tempo` → **앱 재시작 없이** Tempo `/api/search`에서 같은 트레이스 5건 조회 |
| 포트 우회 | 이 호스트에서 `16686`·`4318`이 다른 프로젝트 Jaeger에 잡혀 있어 `16687`·`4319`로 옮겨 떴습니다 |
| 격리 | `apm.sh`가 띄우고 내린 것은 컬렉터·백엔드뿐. `dev.sh`의 postgres·기관 대역은 그대로 |
| SigNoz | 띄웠습니다(컨테이너 6개). 전용 설치기가 compose 를 생성하므로 프로필에 적지 않고 생성 결과를 띄웁니다. **트레이스 수신은 확인하지 못했습니다** — reports/14 §4 |
| SkyWalking | 띄웠습니다(OAP·UI·BanyanDB). 자체 에이전트 경로가 동작하고 서비스·엔드포인트 지표가 보입니다. 기동까지 세 번 막혔습니다 — reports/14 §4 |
| 남은 백엔드 | Pinpoint·Datadog은 프로필과 전환 경로만 있고 **아직 띄워 보지 않았습니다**(reports/14 §9) |

**처음에 두 번 틀렸습니다.** ① 프로필만 주고 `docker compose up`을 해서 기본 파일의 모든 서비스를 함께
띄웠고, 포트 충돌로 멈췄습니다 — 서비스 이름을 지정하게 고쳤습니다. ② 포트를 고르고 나서 앞선 백엔드를
내렸더니, 아직 살아 있는 컬렉터가 `4317`을 쥐고 있어 다음 실행이 `4319`로 밀려났습니다 — 순서를 뒤집어
"먼저 내리고 그 다음 포트를 고른다"로 고쳤습니다. 둘 다 띄워 보기 전에는 보이지 않는 종류입니다.
