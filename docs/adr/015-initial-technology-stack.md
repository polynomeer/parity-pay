# ADR-015 초기 기술 스택의 선택 근거 (재구성)

- Status: Accepted
- Decided: 2026-09-07 (스택이 커밋된 날)
- Date: 2026-10-05 (이 문서를 쓴 날)

> 이 문서는 2026-10-05에 작성했으며, 첫 커밋들(`cfcaa3d` 설계 문서, `0b340a2` Gradle·Docker Compose 구성,
> `2041438`~`ff75497` 첫날 구현)에서 보이는 기술 선택과 기존 문서(docs/05 §2, ADR-001·002·005·006, docs/10)를
> 근거로 선택 이유를 재구성한 것입니다. 당시 저장소에는 스택 표(docs/05 §2)의 "목적" 한 줄만 있었고 대안 비교는
> 없었습니다. 아래 "프로젝트 요구"는 저장소 문서에서 가져왔고, 기술의 성질은 각 절에 단 1차 자료에서 가져왔습니다.
> 저장소가 이유를 말하지 않는 부분은 "재구성"이라고 표시했습니다.

> **에이전트 영향**: 스택을 바꾸려면 이 ADR의 해당 절을 대체하는 새 ADR을 씁니다(docs/05 §2 강제 규칙).
> 아키텍처 형태(ADR-001)와 시스템 오브 레코드(ADR-002), 이벤트 전달 방식(ADR-005·006)은 이미 각자의 ADR이
> 있으므로 여기서는 다루지 않습니다.

## Context

스택을 고를 때 기준이 된 요구는 저장소 문서에 있습니다.

- 금융 효과를 하나의 로컬 트랜잭션과 DB 제약으로 보호합니다(ADR-001, ADR-002, docs/05 §7).
- DB 트랜잭션 안에서 외부 네트워크를 호출하지 않습니다. 외부 호출 결과가 불명확하면 `UNKNOWN`입니다(docs/05 §7, ADR-007).
- 이벤트 전달은 at-least-once로 가정하고, 한 Aggregate의 이벤트 순서는 파티션 키로 유지합니다(docs/05 §9).
- 컨테이너 기반으로 테스트와 장애 시나리오를 반복 실행할 수 있어야 합니다(NFR-008).
- `traceId`, 업무 ID, 원장 ID, 이벤트 ID를 연결할 수 있어야 합니다(NFR-005). 불변조건 위반은 한 건이라도 경보합니다(docs/05 §12).
- 개인 프로젝트이며 분산 기술은 해결할 문제가 명확할 때 단계적으로 도입합니다(docs/01 §3 원칙 6).

## Decision

| 영역 | 첫 커밋의 선택 | 근거 위치 |
|---|---|---|
| 언어·런타임 | Java 21 (Gradle toolchain) | `0b340a2` build.gradle.kts |
| 프레임워크 | Spring Boot 3.5.16 | `0b340a2` build.gradle.kts |
| 영속성 | Spring Data JPA + 경합 경로는 `JdbcTemplate` SQL | `ff75497` 어댑터 |
| 스키마 | Flyway, `ddl-auto: validate` | `ff75497` application.yml |
| 브로커 | Redpanda v24.3.6 (Kafka 프로토콜), Spring Kafka 클라이언트 | `0b340a2` docker-compose.yml |
| 캐시 | Redis 7 컨테이너만 기동, 애플리케이션은 사용하지 않음 | `0b340a2`, ADR-002 Outcome |
| 테스트 | JUnit 5, Testcontainers(PostgreSQL·Redpanda), jqwik, ArchUnit | `0b340a2`, `ff75497` |
| 관측성 | Micrometer → Prometheus·Grafana, Micrometer Tracing(OTel 브리지) → OTLP → Jaeger all-in-one | `ff75497` |
| 실행 환경 | Docker Compose (로컬 실행과 장애 실험 전용) | `0b340a2` |

## Alternatives

### 1. 언어·프레임워크: Java 21 + Spring Boot 3.5

필요한 것: 선언적 트랜잭션 경계(애플리케이션 서비스가 경계를 소유, docs/05 §6), 성숙한 PostgreSQL·Kafka
클라이언트, 모듈 경계를 빌드와 테스트로 강제할 수단(ArchUnit, ADR-001).

| 대안 | 요구에 대한 비교 |
|---|---|
| Java 21 + Spring Boot 3.5 (선택) | JDK 21은 2023-09-19 GA이고 대부분의 공급자가 LTS로 지정합니다([OpenJDK JDK 21](https://openjdk.org/projects/jdk/21/)). Spring Boot 3.5.16은 Java 17 이상을 요구하고 Java 25까지 호환됩니다([System Requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)). 선언적 트랜잭션은 Spring AOP로 동작합니다([Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)). |
| Kotlin + Spring Boot | 같은 생태계를 쓰므로 위 요구는 동일하게 충족합니다. 저장소에는 Kotlin을 뺀 이유가 없습니다. (재구성) 작성자의 실무 언어가 Java였다는 점 외에는 비교할 근거를 찾지 못했습니다. |
| Go, Node.js 등 다른 런타임 | 저장소에 검토 기록이 없습니다. (재구성) 이 프로젝트의 검증 대상은 언어 성능이 아니라 트랜잭션·멱등성·복구 규칙이므로, 익숙한 스택에서 규칙 쪽에 시간을 쓰는 편을 택한 것으로 봅니다. |

비용: Spring의 프록시 기반 트랜잭션은 같은 클래스 내부 호출에 적용되지 않는 등 동작을 알아야 하는 지점이 있고,
JPA는 쓰기 지연(flush 시점) 때문에 SQL이 실제로 나가는 시점이 코드 순서와 다를 수 있습니다. 후자는 실제로
잠금 보유 시간 문제로 드러났습니다(reports/11, 이후 커밋).

첫날 `spring.threads.virtual.enabled: true`로 가상 스레드를 켰습니다(`ff75497` application.yml). 이 선택의
근거는 첫날 문서에 없고 ADR-014(2026-09-16)의 측정에서 처음 다뤄집니다.

### 2. 영속성과 스키마: JPA + JDBC, Flyway

필요한 것: 잔액 차감은 `WHERE available_amount >= :amount AND version = :expected`를 담은 단일 UPDATE여야 하고
(ADR-004), 원장 불변조건은 트리거와 CHECK로 DB에 있어야 하며(ADR-002), Outbox 선점은 `FOR UPDATE SKIP LOCKED`
입니다(docs/05 §9).

| 대안 | 요구에 대한 비교 |
|---|---|
| JPA + 경합 경로는 SQL (선택) | 단순 CRUD는 JPA, 잔액 차감·멱등 레코드·Outbox 선점·소비 이력은 `JdbcTemplate`로 SQL을 그대로 씁니다. docs/05 §2는 "Spring Data JPA + 선택적 jOOQ/JDBC"로 이 분리를 미리 적었습니다. |
| JPA만 사용 | 조건부 UPDATE·`SKIP LOCKED`를 표현하려면 결국 네이티브 쿼리가 필요합니다. |
| jOOQ | 타입 안전 SQL이 장점이지만 코드 생성 단계가 하나 늘어납니다. (재구성) 첫날에는 쓰지 않았고 저장소에 이유는 없습니다. |

스키마는 Flyway만 바꾸고 Hibernate는 `ddl-auto: validate`로 검증만 합니다. Flyway는 대기 중인 버전 마이그레이션을
순서대로 적용합니다([Flyway Migrations](https://documentation.red-gate.com/fd/migrations-271585107.html)).
트리거·지연 제약처럼 ORM이 생성하지 못하는 객체를 SQL 파일로 버전 관리해야 했으므로 SQL 기반 도구가 맞았습니다.
Liquibase는 저장소에서 검토되지 않았습니다.

### 3. 브로커: Redpanda (Kafka 프로토콜)

필요한 것: at-least-once 전달 실험, Aggregate ID 파티션 키로 순서 유지, 커밋 전 종료·ACK 유실·소비자 재시작
같은 장애 주입(docs/10 F-003~F-005). docs/05 §2는 "Kafka 또는 Redpanda"로 열어 두었습니다.

| 대안 | 요구에 대한 비교 |
|---|---|
| Redpanda (선택) | Kafka 프로토콜 0.11 이후 클라이언트가 거의 변경 없이 동작한다고 밝힙니다([Redpanda Kafka clients](https://docs.redpanda.com/current/develop/kafka-clients/)). 단일 컨테이너로 뜨고 Testcontainers에 Redpanda 모듈이 있습니다([Testcontainers](https://java.testcontainers.org/)). 애플리케이션은 Spring Kafka만 알므로 Apache Kafka로 바꿔도 코드는 그대로입니다. |
| Apache Kafka | 같은 키의 이벤트는 같은 파티션에 쓰이고 소비자는 쓰인 순서대로 읽으며, 소비 후에도 보존 기간까지 삭제되지 않습니다([Kafka Introduction](https://kafka.apache.org/43/getting-started/introduction/)). 프로토콜이 같으므로 요구 충족 면에서는 Redpanda와 차이가 없습니다. (재구성) 로컬·테스트에서 단일 바이너리로 빠르게 뜨는 쪽을 고른 것으로 보이며, 저장소에 둘을 비교한 기록은 없습니다. |
| RabbitMQ (classic·quorum queue) | 큐는 소비자가 처리한 메시지를 삭제하므로 소비한 메시지를 다시 읽을 수 없습니다([RabbitMQ Streams](https://www.rabbitmq.com/docs/streams)). 프로젝션 재구축과 재전달 실험에는 로그형 브로커가 맞습니다. |
| 브로커 없이 Outbox 테이블을 소비자가 직접 폴링 | 인프라가 하나 줄지만 "브로커 ACK 유실", "소비자 그룹 재조정" 같은 실험 대상이 사라집니다. 원칙 6에 따르면 미룰 수도 있었던 선택이며, 이 프로젝트는 그 장애 자체를 검증 대상으로 삼았기 때문에 처음부터 넣었습니다(docs/05 §2 "at-least-once 이벤트 전달 실험"). |

프로듀서 설정은 `acks=all`, `enable.idempotence=true`입니다. Kafka 문서는 `acks=all`에서 리더가 동기화된
복제본 전체의 확인을 기다리고, 멱등 프로듀서가 각 메시지를 스트림에 정확히 한 번 쓴다고 설명합니다
([Producer Configs](https://kafka.apache.org/43/configuration/producer-configs/)). 이것은 프로듀서 세션 안의
보장이므로 종단 간 중복 방지는 여전히 소비자 쪽(ADR-006)이 맡습니다.

비용: 로컬 단일 노드 Redpanda는 복제가 없으므로 `acks=all`의 내구성 의미가 운영 클러스터와 다릅니다.
Redpanda가 밝힌 비호환 항목(예: KIP-890 서버 측 트랜잭션 방어)은 이 프로젝트가 Kafka 트랜잭션을 쓰지 않으므로
첫날 범위에는 걸리지 않았습니다.

### 4. 캐시: Redis는 띄우되 쓰지 않음

docs/05 §2는 Redis의 용도를 "속도 제한·TTL 캐시·임시 인증 데이터"로 한정하고 "잔액이나 원장의 진실을 저장하지
않습니다"라고 적었습니다. ADR-002는 Redis 중심 잔액을 대안에서 뺐습니다.

| 대안 | 비교 |
|---|---|
| 첫날 애플리케이션에서 사용하지 않음 (선택) | 로그인 실패 제한도 PostgreSQL 테이블과 별도 트랜잭션으로 구현했습니다(`LoginAttemptTracker`). 컨테이너는 `--appendonly no`로 떠 있습니다. |
| Redis를 잔액·멱등 키 저장소로 사용 | Redis 문서는 RDB 스냅샷만 쓰면 비정상 종료 시 최근 몇 분의 데이터를, AOF 기본 정책(`everysec`)에서도 1초의 데이터를 잃을 수 있다고 설명합니다([Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)). 원장과 같은 트랜잭션에 묶이지 않는다는 점이 더 큰 문제입니다(ADR-002). |

비용: 쓰지 않는 컨테이너가 로컬 스택에 하나 더 있습니다. (재구성) 속도 제한을 도입할 때를 위한 자리로 보이며,
이후 Redis는 분산락 비교 실험(ADR-004 Outcome, M-024~M-028)에만 쓰였습니다.

### 5. 테스트: Testcontainers와 실제 PostgreSQL

필요한 것: 불변조건의 일부가 PostgreSQL 트리거·지연 제약·CHECK에 있으므로(ADR-002, V3) 테스트도 같은 DB에서
돌아야 합니다. NFR-008은 컨테이너 기반 재현을 요구합니다.

| 대안 | 비교 |
|---|---|
| Testcontainers (선택) | 테스트마다 실제 데이터베이스·브로커를 일회용 컨테이너로 띄웁니다([Testcontainers](https://java.testcontainers.org/)). 트리거와 `SKIP LOCKED`가 운영과 같은 엔진에서 검증됩니다. |
| 인메모리 DB(H2 등) | PL/pgSQL 트리거를 그대로 실행할 수 없으므로 INV-001·INV-006 강제를 시험할 수 없습니다. (재구성) 저장소는 H2를 언급하지 않지만, 위 요구에서 바로 따라 나오는 결론입니다. |
| 공용 개발 DB | 테스트 간 격리와 반복 실행(NFR-008)이 깨집니다. |

jqwik(속성 기반)과 ArchUnit(모듈 경계)은 각각 ADR-003 Validation과 ADR-001 Validation이 요구한 도구입니다.
비용: Docker가 없으면 백엔드 테스트가 돌지 않고, 컨테이너 기동 시간만큼 테스트가 느립니다.

### 6. 관측성: Micrometer, Prometheus·Grafana, OpenTelemetry·Jaeger

필요한 것: 불변조건 위반 지표(평소 0)와 즉시 경보, `UNKNOWN` 체류·Outbox 적체 같은 업무 지표(docs/05 §12),
요청과 이벤트를 잇는 트레이스(NFR-005).

| 대안 | 비교 |
|---|---|
| Micrometer + Prometheus·Grafana, Micrometer Tracing(OTel 브리지) → OTLP → Jaeger (선택) | Spring Boot Actuator는 트레이서 파사드인 Micrometer Tracing을 자동 구성하고, OpenTelemetry와 OTLP 내보내기를 지원합니다([Spring Boot Tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html)). Jaeger all-in-one은 로컬 시험용 단일 실행 파일이고 OTLP를 4317(gRPC)·4318(HTTP)로 받습니다([Jaeger Getting Started](https://www.jaegertracing.io/docs/1.65/getting-started/)). 컬렉터를 따로 두지 않았습니다. |
| 로그만으로 추적 | NFR-005의 ID 연결은 가능하지만 "위반 0건이 아니면 경보"를 지표로 걸 수 없습니다. |
| 상용 APM | 저장소에 검토 기록이 없습니다. (재구성) 로컬 Compose 안에서 끝나야 하는 장애 실험 구조와 맞지 않습니다. |

비용: Jaeger all-in-one은 인메모리 저장소라 재시작하면 트레이스가 사라집니다. 로컬 실험용 선택입니다.

### 7. 실행 환경: Docker Compose

첫날의 배포 대상은 로컬 실행과 장애 실험뿐이었습니다(docs/05 §2 "로컬 실행과 장애 실험"). 배포 형태는
ADR-011(2026-09-10)에서 따로 결정합니다.

## Consequences

- 핵심 정합성 규칙이 PostgreSQL에 모이므로 JVM·프레임워크 선택은 규칙의 정확성에 영향을 주지 않습니다. 대신
  JPA의 쓰기 지연처럼 SQL 발행 시점을 바꾸는 프레임워크 동작이 성능 문제로 나타날 수 있습니다.
- 브로커는 프로토콜(Kafka)에 묶이고 제품(Redpanda)에는 묶이지 않습니다.
- Redis가 금융 경로에 없으므로 Redis 장애가 금융 정합성에 영향을 주지 않습니다.
- 모든 통합 테스트가 Docker에 의존합니다.

## Validation

- 브로커 교체 가능성: Apache Kafka 이미지로 Testcontainers 통합 테스트를 돌려 결과가 같은지 확인합니다. 아직 하지 않았습니다(TBD).
- 인메모리 DB 배제 근거: 원장 트리거 시험(`LedgerPostingIntegrationTest`)이 실제 PostgreSQL에서만 통과하는지는
  따로 확인하지 않았습니다(TBD).
- 나머지 항목은 각 기술의 1차 자료와 첫날 커밋의 구성으로 확인한 것이며, 성능 비교 측정은 없습니다.

## Outcome — 2026-10-05

재구성 문서입니다. 이후 바뀐 것은 다음과 같고, 이 문서는 첫날 기준을 유지합니다.

- Spring Boot는 이후 4.x로 올라갔습니다(CLAUDE.md §1 기준). 버전 상향 근거는 이 문서 범위 밖입니다.
- 프론트엔드(ADR-012), 배포 형태(ADR-011), 외부 호출 격리(ADR-014)는 첫날 이후에 결정되었습니다.
