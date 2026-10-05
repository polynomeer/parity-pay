# formal — UNKNOWN 해소 규칙의 모델 검사

부하·장애 실험 42종은 **시각이 맞아떨어지는 순간**에만 드러나는 경로를 우연에 기대서 밟습니다. 모델
검사는 순서를 전부 밟습니다. 이 디렉터리는 `PaymentRecoveryService.resolveOne`의 규칙을 TLA+로 옮기고
TLC로 전수 검사한 것입니다.

## 무엇을 검사하는가

`UnknownResolution.tla`는 구현된 규칙을 그대로 옮긴 것입니다. `UnknownResolutionEscalate.tla`는 전이
**하나만** 다릅니다 — 연속 "없음"을 `FAILED`로 확정하는 대신 사람에게 넘깁니다.

| 불변조건 | 뜻 |
| --- | --- |
| `ExactlyOnce` | 원장 전기가 1회를 넘지 않는다 (INV-004) |
| `NoFalseFailure` | 기관이 승인했는데 결제가 FAILED 로 확정된 상태가 없다 |
| `NoFalseSuccess` | 기관에 기록이 없는데 APPROVED 로 확정된 상태가 없다 |
| `LedgerImpliesCharge` | 원장이 있으면 기관에도 승인이 있다 |

## 실행

```bash
java -cp tla2tools.jar tlc2.TLC -config MC.cfg UnknownResolution.tla
java -cp tla2tools.jar tlc2.TLC -config MC-escalate.cfg UnknownResolutionEscalate.tla
```

`tla2tools.jar`는 커밋하지 않습니다. 받는 법:

```bash
curl -sL -o formal/tla2tools.jar \
  https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar
```

## 창을 넣은 변주 (`UnknownResolutionWindow.tla`, ADR-016)

구현된 규칙에서 `NoFalseFailure`가 깨지는 이유는 하나입니다 — 기관이 요청을 받아 둔 채 **언제까지고** 기록할
수 있기 때문입니다. 임계치를 올려도 그 경로는 남습니다. `UnknownResolutionWindow.tla`는 기관이 계약으로 창을
선언한 경우를 모델에 넣습니다: 창이 닫히면 기관은 더 이상 기록하지 않고, 복구는 **창이 닫힌 뒤에만** 연속
"없음"을 확정합니다.

```bash
java -cp tla2tools.jar tlc2.TLC -config MC-window.cfg UnknownResolutionWindow.tla     # 임계치 2
java -cp tla2tools.jar tlc2.TLC -config MC-window-t3.cfg UnknownResolutionWindow.tla  # 임계치 3
```

네 불변조건이 전부 성립합니다(임계치 2에서 256 상태, 3에서 364 상태). **증명된 것은 "구현이 안전하다"가 아니라
"기관이 창을 지키면 규칙이 안전하다"입니다.** 창은 가정이고 모델은 가정을 검증하지 못합니다 — 바뀐 것은 그
가정이 임계치 숫자 안에 숨어 있다가 설정과 계약으로 나왔다는 점입니다. 결정과 근거는
[ADR-016](../docs/adr/016-not-found-settlement-window.md)에 있습니다.

## 결과 요약

구현된 규칙에서 `NoFalseFailure`가 **5단계 만에** 깨집니다. 임계치를 2 → 3 → 5 → 8로 올려도 깨지고,
탐색만 깊어집니다(42 → 82 → 210 → 502 상태). 나머지 세 불변조건은 98개 상태를 전부 밟고도 깨지지
않습니다. 처치 갈래에서는 넷 다 깨지지 않습니다.

반례는 이렇습니다.

1. 승인 요청이 나갔고 결제는 `UNKNOWN`, 기관은 처리 중(`inflight`)입니다.
2. 복구가 조회합니다. 기관이 아직 기록하지 않았으므로 "없음"입니다.
3. 다시 조회합니다. 여전히 "없음"이고, 연속 2회이므로 `FAILED`로 확정합니다.
4. **그 다음에** 기관이 승인을 기록합니다.

고객은 청구됐고 화면은 실패입니다. `PaymentRecoveryService`의 주석이 바로 이것을 걱정하고 있고
("외부가 요청을 받고 기록하기 직전일 수도 있고"), 임계치가 그 완화책입니다. 모델 검사가 말하는 것은
**임계치가 확률을 낮출 뿐 경로를 없애지 못한다**는 것입니다. `inflight`는 임의로 길 수 있으니까요.

## 왜 실험이 못 찾았는가

운이 아니라 구조입니다. 시험 대역(`MockPgBehavior`)의 상태는 넷뿐입니다.

| 모드 | 기관 기록 |
| --- | --- |
| `NORMAL` | 즉시 있음 |
| `EXPLICIT_DECLINE` | 거절로 있음 |
| `TIMEOUT_BEFORE_APPROVAL` | **영원히 없음** |
| `TIMEOUT_AFTER_APPROVAL` | 끊기기 전에 이미 있음 |

반례가 사는 상태 — **받았고, 승인할 것이고, 아직 기록하지 않은** 창 — 이 대역에 없습니다. 42종을
몇 번 돌리든 도달할 수 없습니다. 대역의 상태 공간이 현실보다 좁으면 그 차이만큼은 시험이 아니라
가정입니다.

## 한계

- **명세는 코드가 아닙니다.** 손으로 옮긴 것이고, 옮기면서 틀리면 검사도 틀립니다. 여기서 검사한 것은
  `resolveOne`의 분기 구조이지 트랜잭션 경계·잠금·DB 제약이 아닙니다.
- **결제 하나짜리 모델입니다.** 복구 작업 여러 대, 리더 재선출, 배치 경합은 들어 있지 않습니다.
- **시간이 없습니다.** grace·백오프·lease는 전이 순서로만 표현됩니다. 임계치를 올리면 실제로는 창이
  줄어드는데, 이 모델은 그것을 재지 않습니다 — 경로가 남는다는 것만 말합니다.
- **처치 갈래를 채택하지 않았습니다.** 자동 확정을 포기하면 사람이 볼 건이 늘어납니다. 그 값은 이
  모델이 재는 것이 아닙니다.
