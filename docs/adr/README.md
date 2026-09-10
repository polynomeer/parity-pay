# Architecture Decision Records

> **에이전트 지침**
> - **읽는 시점**: 설계 대안을 고민할 때, "왜 이렇게 되어 있지?"를 확인할 때, 기존 결정과 다른 방식을 도입하려 할 때.
> - **강제 규칙**: ADR에 기록된 결정을 코드에서 임의로 뒤집지 않습니다. 다른 방식이 필요하면 새 ADR을 `Proposed`로 작성하고 사용자에게 확인합니다. 상태를 `Accepted`로 바꾸는 것은 Validation 항목의 실험·구현이 실제로 끝난 뒤에만 합니다.

ADR은 중요한 기술 선택의 맥락, 대안, 결정과 결과를 기록합니다. 구현 전 문서는 `Proposed`이며 실험 또는 구현으로 확인한 뒤 `Accepted`, 대체되면 `Superseded`로 변경합니다.

| ADR | 제목 | 현재 상태 |
|---|---|---|
| [ADR-001](001-modular-monolith.md) | 모듈러 모놀리스로 시작 | Accepted (2026-09-06) |
| [ADR-002](002-postgresql-system-of-record.md) | PostgreSQL을 금융 원장의 시스템 오브 레코드로 사용 | Accepted (2026-09-06) |
| [ADR-003](003-double-entry-ledger.md) | 이중부기·불변 원장 사용 | Accepted (2026-09-06) |
| [ADR-004](004-atomic-balance-update.md) | 지갑 잔액 조건부 원자 업데이트 | Accepted (2026-09-06, 비교 측정 근거) |
| [ADR-005](005-transactional-outbox.md) | Transactional Outbox 사용 | Accepted (2026-09-06) |
| [ADR-006](006-at-least-once-idempotent-consumer.md) | at-least-once와 멱등 소비자 | Accepted (2026-09-06) |
| [ADR-007](007-unknown-state.md) | 외부 결과 불명확 상태 UNKNOWN 도입 | Accepted (2026-09-06) |
| [ADR-008](008-ledger-balance-snapshot.md) | 원장과 잔액 스냅샷 분리 | Accepted (2026-09-06) |
| [ADR-009](009-reversal-adjustment.md) | 취소·보정을 새 분개로 기록 | Accepted (2026-09-06) |
| [ADR-010](010-refresh-token-cookie.md) | 리프레시 토큰을 httpOnly 쿠키로 옮김 | Accepted (2026-09-10) |

각 ADR 하단의 **Outcome** 절에 무엇을 실제로 검증했고 무엇을 아직 측정하지 않았는지 적혀
있습니다. `Accepted`는 "결정을 채택했고 그 근거를 확인했다"는 뜻이며, "모든 Validation 항목을
측정했다"는 뜻이 아닙니다. 남은 측정은 Outcome에 명시되어 있습니다.

## 작성 형식

새 ADR은 `NNN-kebab-case-title.md`로 만들고 아래 항목을 모두 채웁니다.

- Context: 해결할 문제와 제약
- Decision: 선택한 방법
- Alternatives: 검토한 대안
- Consequences: 긍정·부정 결과
- Validation: 결정을 검증할 실험과 수치
- Status: Proposed / Accepted / Superseded / Rejected
