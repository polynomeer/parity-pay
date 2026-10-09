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
  skywalking: {
    port: process.env.PARITYPAY_SKYWALKING_UI_PORT || "18080",
    async run(page, base, shot) {
      await page.goto(`${base}/General-Service/Services`, { waitUntil: "networkidle" });
      await page.waitForSelector("text=/pay-api/", { timeout: 60000 });
      // 트레이스 화면은 **왼쪽 메뉴가 아니라** 대시보드 아래 탭에 있습니다. 해시 라우트나
      // /General-Service/Trace 같은 주소로는 못 갑니다(404). 그리고 그 탭은 화면 아래에 있어
      // **스크롤해서 그려지기 전에는 DOM 에도 없습니다** — 먼저 끝까지 내립니다.
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForTimeout(3000);
      await page.getByText("Trace", { exact: true }).first().click();
      await page.waitForSelector("text=/Run Query/", { timeout: 30000 });
      // 기본 목록은 스케줄 작업이 채웁니다(6 ms짜리). 느린 결제만 남기려고 최소 지속시간을 겁니다.
      await page.locator('input[type="number"]').first().fill("2000");
      await page.getByText("Run Query").first().click();
      await page.waitForSelector("text=/POST:\\/api\\/v1\\/payments/", { timeout: 30000 });
      await page.waitForTimeout(2000);
      await shot("trace-list");
      // 결제 트레이스 한 건을 엽니다. 그 줄의 Show 를 눌러야 호출 트리가 나옵니다.
      const row = page.locator("tr", { hasText: "POST:/api/v1/payments" }).first();
      await row.getByText("Show").first().click();
      await page.waitForSelector("text=/Total Spans/", { timeout: 30000 });
      await page.waitForTimeout(3000);
      await shot("trace");
    },
  },
  signoz: {
    port: process.env.PARITYPAY_SIGNOZ_PORT || "8080",
    async run(page, base, shot) {
      // 로그인하지 않습니다. 첫 관리자 계정을 만드는 것은 사용자의 몫입니다.
      // 이 화면 자체가 기록할 가치가 있습니다 — **계정을 만들기 전에는 수집도 되지 않습니다**
      // (조직이 없으면 OpAMP 서버가 ingester 를 등록하지 못하고, ingester 는 OTLP 포트를 열지 않습니다).
      await page.goto(`${base}/`, { waitUntil: "networkidle" });
      await page.waitForURL(/signup|login/, { timeout: 30000 }).catch(() => {});
      await page.waitForTimeout(4000);
      await shot("signup");
    },
  },
  elastic: {
    // 화면은 Kibana 입니다. 보안을 꺼 둔 로컬 랩이라 로그인이 없습니다 — 다른 도구와 달리
    // 자격 증명을 넘기지 않습니다.
    port: process.env.PARITYPAY_KIBANA_PORT || "5601",
    async run(page, base, shot) {
      const range = "rangeFrom=now-30m&rangeTo=now&environment=ENVIRONMENT_ALL";
      // Kibana 는 **첫 화면을 그리는 데 오래 걸립니다**(번들을 내려받습니다). 다른 도구의
      // 30초로는 모자라고, 힙을 700MB 로 묶어 두면 더 느립니다.
      // **networkidle 로 기다리지 않습니다.** Kibana 는 화면을 그린 뒤에도 폴링을 계속해서
      // 네트워크가 조용해지는 순간이 오지 않습니다 — 30초 timeout 으로 두 번에 한 번 실패했습니다.
      // 그려졌는지는 아래 waitForSelector 가 판단합니다.
      await page.goto(`${base}/app/apm/services?${range}`, { waitUntil: "domcontentloaded" });
      await page.waitForSelector("text=/pay-api/", { timeout: 120000 });
      // 사용 통계 수집 배너가 화면 위쪽을 두 줄 차지합니다. 다른 도구의 캡처와 높이를 맞추려면
      // 먼저 치웁니다.
      await page.getByRole("button", { name: "Dismiss" }).first().click({ timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(4000);
      await shot("services");
      // 느린 결제 하나를 엽니다. 트랜잭션 이름을 주소에 넣으면 목록을 거치지 않습니다.
      const name = encodeURIComponent("POST /api/v1/payments");
      await page.goto(
        `${base}/app/apm/services/pay-api/transactions/view?transactionName=${name}&transactionType=request&${range}&comparisonEnabled=false`,
        { waitUntil: "domcontentloaded" });
      // 폭포 그림이 그려질 때까지 기다립니다. 글자가 아니라 "Trace sample" 영역을 봅니다.
      await page.waitForSelector("text=/Trace sample|Latency distribution/i", { timeout: 120000 });
      await page.getByRole("button", { name: "Dismiss" }).first().click({ timeout: 5000 }).catch(() => {});
      // 폭포 그림은 **화면 아래에 있습니다.** 그냥 찍으면 지연 차트만 나오고 다른 도구의
      // 트레이스 화면과 비교할 수 없습니다.
      await page.getByText("Trace sample", { exact: false }).first()
        .scrollIntoViewIfNeeded({ timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(6000);
      await shot("transaction");
    },
  },
  openobserve: {
    port: process.env.PARITYPAY_OPENOBSERVE_PORT || "5080",
    async run(page, base, shot) {
      // 로그인해야 화면이 나옵니다. 자격 증명은 scripts/apm.sh 가 만든 값이고 이 파일에는
      // 적지 않습니다 — 환경변수로만 받습니다.
      const user = process.env.OPENOBSERVE_USER;
      const pass = process.env.OPENOBSERVE_PASSWORD;
      if (!user) throw new Error("OPENOBSERVE_USER / OPENOBSERVE_PASSWORD 가 필요합니다");
      await page.goto(`${base}/web/login`, { waitUntil: "domcontentloaded" });
      await page.waitForTimeout(2500);
      await page.locator('input[type="email"], input[name="email"]').first().fill(user);
      await page.locator('input[type="password"]').first().fill(pass);
      await page.locator('button[type="submit"]').first().click();
      await page.waitForURL(/\/web\/(?!login)/, { timeout: 30000 }).catch(() => {});
      // 스트림 이름은 **stream** 으로 줍니다. stream_name 으로 주면 조용히 무시되고
      // "Select a stream first" 화면에 머뭅니다 — Run query 를 눌러도 같습니다.
      await page.goto(`${base}/web/traces?org_identifier=default&stream=default&period=30m&tab=traces`,
                      { waitUntil: "domcontentloaded" });
      // 조회가 끝나면 건수가 나옵니다. 서비스 이름을 기다리면 목록이 접혀 있을 때 실패합니다.
      await page.waitForSelector("text=/Traces Found/i", { timeout: 60000 });
      await page.waitForTimeout(3000);
      // **느린 것만 남깁니다.** 그냥 두면 목록이 배경 작업(아웃박스 폴러)의 JDBC 스팬으로 덮입니다 —
      // 스케줄 작업은 루트 스팬을 만들지 않아서 그 스팬 하나하나가 1-스팬 트레이스가 되고,
      // 부하가 끝나는 순간부터 그것이 목록의 전부입니다.
      //
      // 질의 상자는 Monaco 입니다. 안쪽 textarea 는 숨겨져 있어 눌리지 않습니다 — 바깥 div 를
      // 눌러야 포커스가 갑니다.
      await page.locator(".monaco-editor").first().click();
      await page.keyboard.type("duration > 2000000");
      await page.waitForTimeout(800);
      await page.getByText("Run query", { exact: false }).first().click();
      await page.waitForTimeout(9000);
      await shot("traces");
      // **결제 트레이스를 골라서** 엽니다. 그냥 첫 줄을 누르면 배경 작업(아웃박스 폴러)이 만든
      // 1-스팬 트레이스가 열립니다 — 스케줄 작업은 루트 스팬을 만들지 않아서 JDBC 스팬 하나가
      // 그대로 트레이스가 되고, 부하가 끝난 뒤에는 목록 맨 위가 전부 그것입니다.
      const pay = page.locator("tr", { hasText: "/api/v1/payments" }).first();
      const row = (await pay.count()) ? pay : page.locator("table tbody tr").first();
      await row.click({ timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(6000);
      await shot("trace");
    },
  },
  uptrace: {
    port: process.env.PARITYPAY_UPTRACE_PORT || "14318",
    async run(page, base, shot) {
      const user = process.env.UPTRACE_USER;
      const pass = process.env.UPTRACE_PASSWORD;
      if (!user) throw new Error("UPTRACE_USER / UPTRACE_PASSWORD 가 필요합니다");
      // 로그인 주소는 /login 이 아니라 **/auth/login** 입니다. 그리고 이메일 칸에
      // type="email" 이 없어서 선택자로 잡히지 않습니다 — 첫 input 이 이메일입니다.
      await page.goto(`${base}/auth/login`, { waitUntil: "domcontentloaded" });
      await page.waitForTimeout(2500);
      await page.locator("input").first().fill(user);
      await page.locator('input[type="password"]').first().fill(pass);
      await page.locator('button[type="submit"]').first().click();
      await page.waitForURL(/overview/, { timeout: 30000 }).catch(() => {});
      await page.waitForTimeout(6000);
      // 개요 화면이 이 도구의 특징입니다 — 트레이스 목록이 아니라 **시스템별 RED 지표**가
      // 첫 화면이고, 느린 외부 호출이 httpclient 행의 p50 으로 바로 드러납니다.
      await shot("overview");
      // 시스템을 지정합니다. 주지 않으면 배경 작업(아웃박스 폴러)의 JDBC 스팬이 목록을 덮습니다.
      await page.goto(`${base}/spans/1?time_dur=1800&system=httpserver%3Apay-api`,
                      { waitUntil: "domcontentloaded" });
      await page.waitForSelector("text=/POST \\/api\\/v1\\/payments/", { timeout: 60000 });
      await page.waitForTimeout(4000);
      await shot("groups");
      // 묶음 → 개별 스팬. **행이 아니라 행 안의 링크**를 눌러야 합니다 — 행을 누르면 아무 일도
      // 일어나지 않습니다(주소가 그대로입니다).
      await page.locator("tr", { hasText: "POST /api/v1/payments" }).first()
        .locator("a").first().click({ timeout: 20000 });
      await page.waitForTimeout(8000);
      await page.locator("table tbody tr a").first().click({ timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(8000);
      await shot("trace");
    },
  },
  pinpoint: {
    port: process.env.PARITYPAY_PINPOINT_WEB_PORT || "8081",
    async run(page, base, shot) {
      // 조회 구간을 **직접** 넣습니다. UI 가 채우게 두면 브라우저 시간대로 벽시계 문자열을 만들고,
      // 서버는 그것을 자기 시간대로 읽습니다. 헤드리스 브라우저는 보통 UTC 라서 한국에서 돌리면
      // 9시간 어긋난 빈 화면이 나옵니다 — 데이터가 멀쩡히 있는데도 "There are no running agents".
      const fmt = (d) => {
        const tz = process.env.CAPTURE_TZ || "Asia/Seoul";
        const parts = new Intl.DateTimeFormat("en-CA", {
          timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit",
          hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
        }).formatToParts(d).reduce((a, x) => ((a[x.type] = x.value), a), {});
        return `${parts.year}-${parts.month}-${parts.day}-${parts.hour}-${parts.minute}-${parts.second}`;
      };
      const to = new Date();
      const from = new Date(to.getTime() - 20 * 60 * 1000);
      await page.goto(`${base}/serverMap/pay-api@SPRING_BOOT?from=${fmt(from)}&to=${fmt(to)}`,
                      { waitUntil: "networkidle" });
      // 서버맵과 산점도가 그려질 때까지 기다립니다. HBase 조회라 느립니다.
      await page.waitForSelector("text=/Apdex/", { timeout: 60000 });
      await page.waitForTimeout(8000);
      await shot("servermap");
      // 산점도의 점 하나를 열면 호출 트리(Call Tree)가 나옵니다. Pinpoint 가 OTel 자동계측과
      // 가장 다른 화면이 여기입니다 — 메서드 단위까지 내려갑니다.
      const dot = page.locator("canvas").last();
      await dot.scrollIntoViewIfNeeded().catch(() => {});
      await page.waitForTimeout(2000);
      await shot("scatter");
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
// **시간대가 중요합니다.** Pinpoint 는 조회 구간을 시간대 없는 벽시계 문자열로 서버에 보냅니다.
// 헤드리스 브라우저는 보통 UTC 라서, 서버가 KST 로 읽으면 9시간 어긋난 빈 화면이 나옵니다.
// 데이터가 멀쩡히 있는데도 "There are no running agents" 가 뜹니다.
const page = await browser.newPage({
  viewport: VIEWPORT,
  httpCredentials,
  timezoneId: process.env.CAPTURE_TZ || Intl.DateTimeFormat().resolvedOptions().timeZone,
});
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
