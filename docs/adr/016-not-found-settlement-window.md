# ADR-016 "기관에 기록이 없음"은 기관이 보장한 창이 닫힌 뒤에만 실패로 확정함

- Status: Accepted
- Decided: 2026-10-05
- Date: 2026-10-05

> **에이전트 영향**: 복구가 외부 조회에서 "없음"을 받았을 때 **연속 확인 횟수만으로 `FAILED`로 확정하지
> 않습니다.** `paritypay.recovery.*.not-found-settle-after`(기관이 "이 요청은 더 이상 기록되지 않는다"고
> 보장하는 창)가 지난 뒤에만 확정합니다. 창이 선언되지 않았으면(설정 없음 또는 음수) **확정하지 않고 사람에게
> 넘깁니다.** 타임아웃은 여전히 `UNKNOWN`이고(ADR-007), 승인 재요청은 여전히 없습니다.

## Context

구현은 "외부에 기록이 없다"가 `not-found-confirm-threshold`회 연속 확인되면 `FAILED`로 확정했습니다.
그 규칙은 네 복구 서비스(충전·결제·취소·지급)에 같은 모양으로 있습니다.

[reports/11 M-030](../../reports/11-performance-failure-report.md)의 TLA+ 모델 검사가 이 규칙에서
`NoFalseFailure`를 깨는 경로를 **깊이 5**에서 찾았습니다.

1. 승인 요청이 기관에 도달했고 결제는 `UNKNOWN`, 기관은 처리 중입니다.
2. 복구가 조회합니다 — 아직 기록되지 않았으므로 "없음".
3. 임계치만큼 반복합니다 — 여전히 "없음" → `FAILED`로 확정.
4. **그 다음에** 기관이 승인을 기록합니다. 고객은 청구됐고 화면은 실패입니다.

**임계치는 이 경로를 없애지 못합니다.** 2 → 8로 올려도 위반이 남고 탐색만 깊어집니다(42 → 502 상태).
처리 중 창이 임의로 길 수 있기 때문입니다. 코드 주석은 이 위험을 알고 있었고 임계치를 완화책으로 적어
두었지만, 완화는 확률을 낮추는 것이지 경로를 닫는 것이 아닙니다.

`docs/09` §7은 "없음"에 대해 **재요청 또는 보류**를 허용했고, 구현은 그 둘이 아닌 **확정**을 골라 두고
있었습니다. 어느 쪽으로 닫을지를 결정하기 위해 업계의 공개 기술자료를 조사했습니다.

### 조사한 것 — 공개 기술자료

| 출처 | 그 문서가 말하는 것 |
|---|---|
| [AWS Builders' Library, *Challenges with distributed systems*](https://aws.amazon.com/builders-library/challenges-with-distributed-systems/) | 응답이 없으면 결과는 `UNKNOWN`이며, 서버가 받았는지조차 알 수 없습니다. **해소 방법은 제시하지 않습니다** — 문제의 정의까지입니다. 이 프로젝트의 ADR-007이 여기까지 와 있었습니다 |
| [Stripe, *Advanced error handling*](https://docs.stripe.com/error-low-level) | "clients are usually left in a state where they don't know whether or not the server received the request. **To get a definitive answer, they should retry such requests with the same idempotency keys** … until they're able to receive a result from the server." 500은 **indeterminate**로 다루고, Stripe 쪽이 전진·후진 중 하나로 **정리(reconcile)**한 뒤 웹훅으로 알립니다 |
| [Stripe, *Idempotent requests*](https://docs.stripe.com/api/idempotent_requests) | 멱등 키는 **24시간**이 지나면 시스템에서 제거되고, 그 뒤 같은 키는 새 요청이 됩니다. 즉 "같은 요청"의 수명에 **경계가 있습니다** |
| [Google Cloud Spanner, *unknown commit status*](https://docs.cloud.google.com/spanner/docs/queues/queues-at-most-once) | 커밋 상태가 불명확한 트랜잭션을 공식 클라이언트는 **자동 재시도하지 않습니다.** 애플리케이션이 멱등 패턴(`ASSERT_ROWS_MODIFIED` 등)으로 처리해야 하며, **커밋되지 않았다고 가정하는 것을 허용하지 않습니다** |
| [Adyen, *Payment result codes*](https://docs.adyen.com/online-payments/build-your-integration/payment-result-codes/) | `Received`·`Pending`은 "최종 상태가 아직 없음"이고 **"minutes, hours, or even days"**가 걸릴 수 있습니다. 웹훅을 기다려야 하며 중간 상태를 거절로 다루지 않습니다. **최대 대기 시간은 문서에 없습니다** |
| [Google SRE, *Eliminating toil*](https://sre.google/sre-book/eliminating-toil/) | toil은 "manual, repetitive, automatable, tactical, devoid of enduring value, and **scales linearly as a service grows**"입니다 |
| [AWS Builders' Library, *Avoiding fallback in distributed systems*](https://aws.amazon.com/builders-library/avoiding-fallback-in-distributed-systems/) | 폴백 경로는 **가장 혼란스러운 순간에만** 실행되므로 그때 동작한다는 보장이 없습니다. 주 경로를 튼튼하게 하거나, 폴백을 **정기적으로 쓰이는 경로로 바꾸라**고 합니다 |

세 가지가 분명해집니다.

1. **"없음"을 실패의 근거로 쓰는 곳이 없습니다.** Spanner는 가정을 금지하고, Stripe는 "정의된 답"을 받을
   때까지 멱등 재요청하라고 하고, Adyen은 중간 상태를 거절로 바꾸지 말라고 합니다. 우리 구현은 세 문서가
   모두 피하라고 하는 추론을 하고 있었습니다.
2. **그러나 어디에도 "영원히 기다려라"는 없습니다.** Stripe의 멱등 키는 24시간이면 사라지고, 그 뒤 같은
   키는 다른 요청입니다. 즉 업계의 방식은 **무한 보류가 아니라 경계가 선언된 대기**입니다.
3. **전부 사람에게 넘기는 것은 답이 아닙니다.** "없음"은 기관 장애 때 건수로 몰려오는 경로이고
   (reports/11 M-031: 그 종류의 타임아웃은 100%가 이 경로입니다), 사람이 보는 일은 정의상 toil이며 서비스
   규모에 비례해 늘어납니다. 게다가 그 경로는 장애 중에만 열리는 폴백이라 **필요한 순간에 처음 쓰입니다.**

## Decision

**"없음"은 기관이 보장한 창이 닫힌 뒤에만 실패의 근거가 됩니다.**

```text
조회 = 없음
  └ 연속 확인 횟수 < 임계치                      → 백오프 후 다시 조회 (그대로)
  └ 연속 확인 횟수 >= 임계치
       ├ 기관이 창을 선언했고 아직 열려 있음      → 확정하지 않고 계속 조회
       ├ 기관이 창을 선언했고 창이 닫힘            → FAILED 로 확정 (근거: 기관의 계약)
       └ 기관이 창을 선언하지 않음                → 확정하지 않고 사람에게 넘김
```

- 창은 설정입니다 — `paritypay.recovery.{top-up,payment,cancellation}.not-found-settle-after`,
  `paritypay.settlement.recovery-not-found-settle-after`. 뜻은 **"이 시간이 지나면 기관은 이 요청을 더 이상
  기록하지 않는다"**이고, 값의 출처는 기관과의 계약입니다.
- 기준 시각은 **요청을 보낸 때**입니다(충전 `requested_at`, 결제 `created_at`, 취소 `requested_at`, 지급은
  상태가 `PAYING`으로 바뀐 `updated_at`).
- 비워 두거나 음수면 "선언하지 않음"이고, 그 기관에 대해서는 자동 확정을 하지 않습니다. 설정으로 표현할 수
  있어야 시험할 수 있으므로 음수도 같은 뜻으로 받습니다.
- **임계치는 그대로 둡니다.** 창은 "기관이 아직 기록할 수 있는가"를, 임계치는 "한 번의 조회 실패를 사실로
  믿지 않는가"를 막습니다. 다른 것을 막으므로 둘 다 있습니다.
- 창을 기다리는 동안은 **수동 검토 예산을 쓰지 않습니다.** 조회가 정상 동작하고 있고 답이 "아직 없음"일
  뿐이므로, 조회 장애(`UNAVAILABLE`)의 8회 한도와 섞지 않습니다.

이 저장소의 값은 **60초**입니다. 근거: 기관 대역(`mock-bank`·`mock-pg`)은 요청을 처리하는 동안에 기록하고
그 밖에서는 기록하지 않으므로 실제 창은 읽기 타임아웃 수준이며, 60초는 거기에 여유를 둔 값입니다. 동시에
클라이언트 폴링 상한 90초(DOC-14 FE-003) 안에 들어가므로 "출금 전 끊김"의 사용자 경험이 바뀌지 않습니다.
**실제 기관을 붙이면 이 값은 그 기관의 계약에서 옵니다.**

## Alternatives

- **전부 사람에게 넘김(M-030의 처치 갈래).** 불변조건 넷을 다 지키지만, 장애 때 건수로 오는 경로를 toil로
  바꿉니다(Google SRE). 장애 중에만 열리는 폴백이라는 문제도 그대로입니다(AWS). 뺍니다.
- **임계치를 올림.** 모델 검사가 효과 없음을 보였습니다(2 → 8, 위반 유지). 뺍니다.
- **멱등 재요청으로 "정의된 답"을 받음(Stripe 방식).** 이론상 가장 깔끔하고 `docs/09` §7이 허용하는
  "재요청"입니다. 그러나 기관이 **처리 중인 같은 키의 요청을 어떻게 다루는지**에 전부 달려 있습니다 —
  저장된 결과를 주거나(안전), 진행 중 충돌을 알려 주거나(안전, Stripe의 409), 아니면 두 번 처리합니다
  (이중 청구). 우리 기관 대역은 그 계약을 **문서화하지 않았습니다.** 계약 없이 승인을 재전송하는 것은
  CLAUDE.md §3이 금지합니다. 기관이 그 계약을 명시하면 그때 ADR을 더 씁니다.
- **무한 보류.** 고객과 판매자가 영원히 답을 못 받습니다. Adyen도 "며칠"이라고 했을 뿐 "무한"이라고 하지
  않았습니다. 뺍니다.

## Consequences

- **"없음"으로 실패가 확정되는 시각이 뒤로 갑니다.** 지금 설정(60초)에서 `max(임계치 도달, 요청 + 60초)`
  입니다. 기존 실측(reports/11 M-031: 43초)이 60초대로 옮겨갑니다. 90초 폴링 상한 안이라 화면 계약은
  유지됩니다.
- **기관을 새로 붙일 때 창을 정해야 합니다.** 정하지 않으면 그 기관의 "없음"은 전부 사람에게 갑니다. 이것은
  비용이지만 **조용히 틀리는 것보다 낫습니다.**
- 창 안에서는 조회가 계속되므로 기관에 가는 조회 수가 늘어납니다. 백오프는 그대로이고(2초 → 최대 10분),
  60초 창에서는 한 건당 조회가 3~5회입니다.
- 모델 검사의 전제가 바뀝니다 — "기관이 언제까지고 기록할 수 있다"가 아니라 "창 안에서만 기록한다"입니다.
  그 전제는 **계약이고 우리가 검증할 수 없습니다.** 기관이 창을 어기면 같은 반례가 돌아옵니다.
- 지표는 그대로입니다(`paritypay.recovery.settled{resolution}`). 창 때문에 사람에게 넘어간 건은
  `MANUAL_REVIEW`로 셉니다 — 창을 선언하지 않은 기관이 있으면 그 수치로 보입니다.

## Validation

- 창이 열려 있는 동안 연속 "없음"이 임계치를 넘겨도 `UNKNOWN`으로 남고 원장·잔액이 움직이지 않는다
- 창이 닫힌 뒤 같은 "없음"이 `FAILED`로 확정된다
- 창이 선언되지 않은 기관에서는 확정하지 않고 수동 검토로 넘어간다
- 조회가 승인·거절을 답하면 창과 무관하게 즉시 확정된다 (기존 동작)
- 모델 검사: 창을 모델에 넣으면 `NoFalseFailure`를 포함한 네 불변조건이 전부 성립한다

## Outcome — 2026-10-05

| 항목 | 결과 |
|---|---|
| 창 안에서 확정하지 않음 | `NotFoundSettleWindowTest` (창 1시간, 네 번 조회해도 `UNKNOWN`·원장 0) |
| 창이 닫힌 뒤 확정 | 같은 시험 — 요청 시각을 두 시간 앞으로 밀자 `FAILED` |
| 창 미선언 → 수동 검토 | `NotFoundWithoutWindowTest` (`-1s` = 선언 없음) |
| 기존 복구 동작 | `TopUpRecoveryIntegrationTest` 12건·`PaymentRecoveryIntegrationTest`·`PgRefundIntegrationTest` 그대로 통과 |
| 모델 검사 | `formal/UnknownResolutionWindow.tla` — 임계치 2에서 256 상태, 임계치 3에서 364 상태, **네 불변조건 모두 위반 없음**. 원본 `reports/data/11-M030-model-checking/tlc-window-20261005.txt` |
| 전체 시험 | `./gradlew test --rerun-tasks --no-build-cache` **334건 통과, 실패·skip 0** (331 → 334) |

모델 검사가 증명한 것은 "구현이 안전하다"가 아니라 **"기관이 창을 지키면 규칙이 안전하다"**입니다. 창은
가정이고 모델은 가정을 검증하지 못합니다. 바뀐 것은 **가정이 코드 밖(계약)으로 나왔다는 것**입니다 —
전에는 "기관이 금방 기록할 것"이라는 가정이 임계치 숫자 안에 숨어 있었습니다.
