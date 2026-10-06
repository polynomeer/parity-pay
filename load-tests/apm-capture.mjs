// APM 도구 화면을 같은 조건으로 캡처합니다 (reports/14 §6).
//
// 손으로 찍으면 창 크기와 조회 구간이 매번 달라져서 도구끼리 비교가 안 됩니다. 그리고 메모리
// 저장소를 쓰는 도구는 보관이 수 분이라, 부하를 넣은 직후에 찍지 않으면 빈 화면이 나옵니다.
//
// 사용:
//   node load-tests/apm-capture.mjs zipkin   --out ../blog/assets/img/posts
//   node load-tests/apm-capture.mjs pinpoint --out /tmp/shots
//
// 주의: URL 에 조회 조건을 넣어도 **자동으로 조회되지 않는 도구가 있습니다**. Jaeger 와 Zipkin 은
// 조건만 채워 두고 조회 버튼을 눌러야 결과가 나옵니다. 그래서 각 대상이 클릭 단계를 가집니다.
// playwright 는 apps/e2e 의 의존성입니다(저장소 루트에는 없습니다). pnpm 배치가 바뀌어도
// 깨지지 않게 e2e 패키지 기준으로 해석합니다.
import { createRequire } from "node:module";
const require = createRequire(new URL("../apps/e2e/package.json", import.meta.url));
const { chromium } = require("@playwright/test");
import { mkdir } from "node:fs/promises";
import path from "node:path";

const VIEWPORT = { width: 1600, height: 1000 };

const args = process.argv.slice(2);
const target = args[0];
const outIndex = args.indexOf("--out");
const outDir = outIndex >= 0 ? args[outIndex + 1] : "/tmp/apm-shots";

const targets = {
  zipkin: {
    port: process.env.PARITYPAY_ZIPKIN_PORT || "9411",
    async run(page, base, shot) {
      const query = "serviceName=pay-api&spanName=post%20%2Fapi%2Fv1%2Fpayments&lookback=15m&limit=20";
      await page.goto(`${base}/zipkin/?${query}`, { waitUntil: "networkidle" });
      // 조건은 URL 이 채우지만 조회는 버튼이 합니다.
      await page.getByText("RUN QUERY", { exact: false }).first().click();
      await page.waitForSelector("text=/2\\.5\\d*s/", { timeout: 20000 });
      await shot("search");
      // 목록의 첫 트레이스를 엽니다. 이것이 "원인까지 몇 단계인가"의 두 번째 단계입니다.
      // 글자("SHOW")가 아니라 링크 주소로 찾습니다 — 글자는 번역·여백에 따라 어긋납니다.
      await page.locator('a[href^="/zipkin/traces/"]').first().click();
      await page.waitForSelector("text=/Duration/i", { timeout: 20000 });
      await page.waitForTimeout(1500);
      // 오른쪽 상세 패널에는 호스트 이름 같은 개인 정보가 그대로 나옵니다. 공개용 캡처에서는
      // 타임라인만 잘라 둡니다.
      await shot("trace", { clip: { x: 0, y: 0, width: 1120, height: 1000 } });
    },
  },
  tempo: {
    // Tempo 에는 화면이 없습니다. Grafana 의 Explore 에서 봅니다 — 그래서 포트가 Grafana 입니다.
    port: process.env.PARITYPAY_GRAFANA_PORT || "3000",
    async run(page, base, shot) {
      // 데이터소스 uid 는 프로비저닝이 만들어 주므로 API 로 찾습니다.
      // Explore 는 익명 Viewer 로 열리지 않고 로그인 화면으로 되돌아갑니다. Basic 인증 헤더도
      // UI 경로에서는 무시되므로 **세션을 한 번 만들어야** 합니다. 자격 증명은 로컬 compose 의
      // 값이고 환경변수로만 받습니다 (이 파일에는 없습니다).
      if (process.env.GRAFANA_USER) {
        const res = await page.request.post(`${base}/login`, {
          data: { user: process.env.GRAFANA_USER, password: process.env.GRAFANA_PASSWORD || "" },
        });
        if (!res.ok()) throw new Error(`Grafana 로그인 실패: ${res.status()}`);
      }
      const auth = process.env.GRAFANA_USER
        ? { Authorization: "Basic " + Buffer.from(`${process.env.GRAFANA_USER}:${process.env.GRAFANA_PASSWORD || ""}`).toString("base64") }
        : {};
      const list = await (await fetch(`${base}/api/datasources`, { headers: auth })).json();
      const uid = list.find((d) => d.type === "tempo")?.uid;
      if (!uid) throw new Error("Tempo 데이터소스가 없습니다");
      const pane = {
        a: {
          datasource: uid,
          queries: [{ refId: "A", datasource: { type: "tempo", uid }, queryType: "traceql",
                      query: '{ name="POST /api/v1/payments" }', limit: 20 }],
          range: { from: "now-15m", to: "now" },
        },
      };
      const url = `${base}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(pane))}`;
      await page.goto(url, { waitUntil: "networkidle" });
      // 결과 표가 그려질 때까지 기다립니다. 글자 모양(2.52 s / 2528)은 Grafana 판올림마다 달라집니다.
      await page.waitForSelector('[role="table"], [role="grid"], table', { timeout: 30000 });
      await page.waitForTimeout(3000);
      await shot("search");
      // 첫 트레이스를 엽니다.
      await page.locator('a[href*="traceId"], [role="row"] a').first().click({ timeout: 15000 }).catch(() => {});
      await page.waitForTimeout(4000);
      await shot("trace");
    },
  },
  pinpoint: {
    port: process.env.PARITYPAY_PINPOINT_WEB_PORT || "8081",
    async run(page, base, shot) {
      await page.goto(`${base}/`, { waitUntil: "networkidle" });
      await page.waitForTimeout(8000);
      await shot("servermap");
    },
  },
};

const spec = targets[target];
if (!spec) {
  console.error(`모르는 대상: ${target} (${Object.keys(targets).join(" | ")})`);
  process.exit(2);
}

await mkdir(outDir, { recursive: true });
const browser = await chromium.launch();
// Grafana 의 Explore 는 익명 Viewer 로는 열 수 없습니다(로그인으로 되돌려 보냅니다). 자격 증명은
// 로컬 compose 의 값이고, 이 파일에는 적지 않습니다 — 환경변수로만 받습니다.
const httpCredentials = process.env.GRAFANA_USER
  ? { username: process.env.GRAFANA_USER, password: process.env.GRAFANA_PASSWORD || "" }
  : undefined;
const page = await browser.newPage({ viewport: VIEWPORT, httpCredentials });
const shot = async (name, options = {}) => {
  const file = path.join(outDir, `apm-lab-${target}-${name}.png`);
  await page.screenshot({ path: file, ...options });
  console.log(`찍음 ${file}`);
};
try {
  await spec.run(page, `http://localhost:${spec.port}`, shot);
} finally {
  await browser.close();
}
