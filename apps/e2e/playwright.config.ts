import { defineConfig } from "@playwright/test";

/**
 * E2E 설정.
 *
 * **목(mock)을 쓰지 않습니다.** 실제 pay-api, 실제 Mock Bank·Mock PG, 실제 브라우저입니다.
 * 그것이 이 시험이 존재하는 이유입니다 — 목은 자기가 흉내 내는 계약이 틀렸을 때 그 사실을
 * 알려 주지 않습니다(결함 J).
 *
 * 스택은 `load-tests/run-e2e.sh`가 띄웁니다. 여기서 띄우지 않는 이유는 Gradle 빌드와 Docker가
 * 필요하고, 그 순서를 셸이 다루는 편이 낫기 때문입니다.
 */
export default defineConfig({
  testDir: "./tests",
  // 복구 확정에 35~45초가 걸립니다(M-011). 그보다 넉넉해야 합니다.
  timeout: 180_000,
  expect: { timeout: 15_000 },
  // 같은 기관 장애 모드를 공유하므로 병렬로 돌리면 서로의 시나리오를 덮어씁니다.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env["E2E_CUSTOMER_URL"] ?? "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
