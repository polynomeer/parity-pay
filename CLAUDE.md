# parity-pay

> Claude Code가 이 저장소에서 작업할 때 따르는 규칙입니다.
> 아직 코드가 없는 초기 상태이므로, 구조가 잡히는 대로 "프로젝트 개요"와 "아키텍처" 섹션을 채워 주세요.

## 프로젝트 개요

- **이름**: parity-pay
- **스택**: Java / Spring Boot (Gradle)
- **설명**: _(TODO: 무엇을 하는 서비스인지 한두 문장으로)_

## 개발 명령어

```bash
./gradlew build           # 컴파일 + 테스트 + 패키징
./gradlew test            # 전체 테스트
./gradlew test --tests "*SomeTest"   # 단일 테스트 클래스
./gradlew bootRun         # 로컬 실행
./gradlew clean build     # 클린 빌드
```

- 빌드 도구 명령은 항상 `./gradlew` 래퍼를 사용합니다. 시스템에 설치된 `gradle`을 직접 호출하지 않습니다.
- 변경을 마치면 최소한 `./gradlew test`를 돌려 통과를 확인하고, 결과를 있는 그대로 보고합니다.

## 아키텍처

_(TODO: 패키지 구조, 모듈 경계, 외부 연동(PG/은행/원장 등)을 여기에 기록)_

## 코드 규칙

- 새 코드는 주변 코드의 네이밍·주석 밀도·관용구를 따릅니다.
- 도메인 로직은 프레임워크 어노테이션에 의존하지 않는 순수 자바로 유지하는 것을 선호합니다.
- 금액은 `double`/`float`를 쓰지 않습니다. `BigDecimal` 또는 최소 화폐 단위의 정수형(`long`)을 사용하고, 통화 정보를 함께 다룹니다.
- 외부 결제 연동은 재시도 시 중복 청구가 없도록 멱등키(idempotency key)를 전제로 설계합니다.
- 로그·예외 메시지에 카드번호, 계좌번호, 개인식별정보를 남기지 않습니다.

## 커밋 규칙 (Conventional Commits)

형식:

```
<type>(<scope>): <subject>

<body>

<footer>
```

**type** (필수, 소문자)

| type | 용도 |
| --- | --- |
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `style` | 포매팅, 세미콜론 등 동작에 영향 없는 변경 |
| `refactor` | 기능 변화 없는 코드 구조 개선 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가/수정 |
| `build` | 빌드 시스템, 의존성 변경 (Gradle 등) |
| `ci` | CI 설정 변경 |
| `chore` | 그 외 잡무 (설정 파일, .gitignore 등) |
| `revert` | 이전 커밋 되돌리기 |

**scope** (선택): 영향 범위를 소문자로. 예) `payment`, `ledger`, `api`, `auth`, `deps`

**subject** (필수)

- 영문 소문자, 명령형 현재시제 ("add", "added"/"adds" 아님)
- 마침표로 끝내지 않음
- 50자 이내 권장, 헤더 전체 72자 초과 금지

**body** (선택): 헤더와 빈 줄로 구분. *무엇을* 보다 *왜*를 씁니다. 한 줄 72자 정도에서 줄바꿈.

**footer** (선택)

- 이슈 연결: `Closes #123`, `Refs #45`
- 파괴적 변경: 헤더 type 뒤에 `!`를 붙이고(`feat(api)!: ...`) 푸터에 `BREAKING CHANGE: <설명>`

예시:

```
feat(payment): add idempotency key to charge request

Retried requests previously created duplicate charges when the PG
timed out. The key is stored with the charge and reused on retry.

Closes #142
```

```
fix(ledger): prevent negative balance on concurrent withdrawal
```

```
refactor!: replace double with BigDecimal in Money

BREAKING CHANGE: Money.of(double) is removed; use Money.of(String).
```

**규칙**

- 커밋 하나는 논리적으로 하나의 변경만 담습니다. 무관한 변경은 나눠서 커밋합니다.
- 커밋 전 빌드/테스트가 통과해야 합니다.
- Claude가 만드는 커밋에는 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 트레일러를 붙입니다.

## 브랜치 규칙

- `main`은 항상 배포 가능한 상태를 유지합니다.
- 작업 브랜치: `<type>/<간단한-설명>` — 예) `feat/idempotent-charge`, `fix/ledger-race`
- `main`에 직접 커밋하지 않습니다. Claude는 커밋/푸시를 사용자가 요청할 때만 수행하며, `main`에 있으면 먼저 브랜치를 만듭니다.

## 하지 말 것

- `git push --force` (필요하면 `--force-with-lease`를, 그것도 사용자 승인 후에)
- 요청 없이 커밋·푸시·PR 생성
- 실패한 테스트를 `@Disabled`나 skip으로 덮는 것 — 원인을 고치거나 사용자에게 보고합니다
- 시크릿(API 키, PG 자격증명)을 저장소에 커밋하는 것 — 환경변수나 로컬 설정 파일을 사용합니다
