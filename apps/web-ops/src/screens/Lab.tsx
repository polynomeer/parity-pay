/**
 * Lab 장애 시뮬레이터 + 불변조건 모니터 (DOC-15 §5).
 *
 * **이 프로젝트의 핵심 데모입니다.** 장애를 주입하고, 복구가 도는 동안 불변조건 카드가 계속
 * 정상으로 유지되는 것을 보여 줍니다.
 *
 * 불변조건 값은 서버 캐시를 읽습니다. 화면이 다시 계산하지 않고, **`value`가 null이면 "정상"이
 * 아니라 "확인하지 못함"으로 표시합니다** — 확인하지 못한 것을 정상이라고 말하면 감시가
 * 거짓말을 합니다.
 */
import { applyBankMode, applyPgMode, getInvariants, type InvariantSnapshot } from "@paritypay/api-client";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { SCENARIOS } from "../lab/scenarios";

/** 불변조건 계산이 이보다 오래됐으면 화면이 그 사실을 드러냅니다. 기본 갱신 주기는 30초입니다. */
const STALE_AFTER_SECONDS = 90;

export function Lab() {
  const [selectedId, setSelectedId] = useState(SCENARIOS[0]!.id);
  const selected = SCENARIOS.find((s) => s.id === selectedId)!;

  const invariants = useQuery<InvariantSnapshot>({
    queryKey: ["invariants"],
    queryFn: () => getInvariants(api),
    // 복구가 도는 동안 계속 보고 있어야 하므로 주기적으로 다시 읽습니다.
    refetchInterval: 5_000,
  });

  const apply = useMutation({
    mutationFn: async () => {
      if (selected.target === "bank") {
        await applyBankMode(api, selected.bankMode ?? "NORMAL");
        return;
      }
      await applyPgMode(api, {
        ...(selected.pgMode !== undefined ? { mode: selected.pgMode } : {}),
        ...(selected.webhookMode !== undefined ? { webhookMode: selected.webhookMode } : {}),
        ...(selected.statusQueryAvailable !== undefined
          ? { statusQueryAvailable: selected.statusQueryAvailable }
          : {}),
      });
    },
  });

  return (
    <section>
      <h1>장애 시뮬레이터</h1>

      <label>
        시나리오
        <select data-testid="scenario" value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>
          {SCENARIOS.map((scenario) => (
            <option key={scenario.id} value={scenario.id}>
              {scenario.label}
            </option>
          ))}
        </select>
      </label>
      {/* 무엇이 일어날지 미리 말해 줍니다. 버튼만 있으면 결과를 해석할 수 없습니다. */}
      <p data-testid="explains">{selected.explains}</p>
      <button type="button" data-testid="apply" onClick={() => apply.mutate()} disabled={apply.isPending}>
        시나리오 적용
      </button>
      {apply.isSuccess && (
        <p role="status" data-testid="applied">
          적용했습니다. 이제 고객 앱에서 충전이나 결제를 실행하고 아래 카드를 보십시오.
        </p>
      )}

      <h2>불변조건</h2>
      <InvariantCards snapshot={invariants.data} />
    </section>
  );
}

function InvariantCards({ snapshot }: { snapshot: InvariantSnapshot | undefined }) {
  if (snapshot === undefined) {
    return <p>불변조건을 불러오는 중입니다.</p>;
  }

  const age = snapshot.ageSeconds ?? null;
  const stale = age === null || age > STALE_AFTER_SECONDS;

  return (
    <>
      {/* 캐시가 멈추면 마지막 값이 계속 '정상'으로 보입니다. 그 위험을 화면에 드러냅니다. */}
      <p data-testid="freshness">
        {age === null
          ? "아직 한 번도 계산하지 않았습니다"
          : stale
            ? `${age}초 전 값입니다 — 갱신이 멈췄을 수 있습니다`
            : `${age}초 전 기준`}
      </p>
      <ul>
        {(snapshot.values ?? [])
          .filter((item) => (item.name ?? "").startsWith("paritypay.invariant."))
          .map((item) => {
            const unknown = item.value === null || item.value === undefined;
            const violated = !unknown && (item.value ?? 0) > 0;
            return (
              <li key={item.name} data-testid="invariant-card">
                <strong>{item.description}</strong>
                <span data-testid="invariant-state">
                  {unknown ? "확인하지 못함" : violated ? `위반 ${item.value}건 — 즉시 대응` : "정상"}
                </span>
              </li>
            );
          })}
      </ul>
    </>
  );
}
