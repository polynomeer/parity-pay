// docs/api/openapi.json → TypeScript 타입.
//
// 스냅샷은 최상위가 OpenAPI 문서가 아니라 `customer`·`operations` 두 문서를 담은 맵입니다
// (springdoc 그룹 설정). 그래서 그대로 openapi-typescript에 넘길 수 없고 그룹을 꺼내야 합니다.
//
// 생성물은 커밋합니다. 빌드 시점에 생성하면 백엔드 저장소 없이 프론트엔드를 빌드할 수 없게
// 됩니다. 근거: docs/14-frontend-design.md §8
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import openapiTS, { astToString } from "openapi-typescript";

const here = dirname(fileURLToPath(import.meta.url));
const snapshotPath = resolve(here, "../../../docs/api/openapi.json");
const snapshot = JSON.parse(readFileSync(snapshotPath, "utf8"));

const banner = (group) => `/**
 * ${group} API 타입입니다. **손으로 고치지 않습니다.**
 *
 * 생성: pnpm --filter @paritypay/api-client generate
 * 출처: docs/api/openapi.json (${group})
 *
 * 백엔드가 응답 필드를 바꾸면 이 파일이 바뀌고, 그것을 쓰는 화면의 빌드가 깨집니다.
 * 그것이 이 파일을 커밋하는 이유입니다.
 */
`;

const scratch = mkdtempSync(join(tmpdir(), "paritypay-openapi-"));

for (const group of ["customer", "operations"]) {
  const document = snapshot[group];
  if (!document) {
    throw new Error(`스냅샷에 '${group}' 그룹이 없습니다. docs/api/openapi.json을 확인하세요.`);
  }
  const groupPath = join(scratch, `${group}.json`);
  writeFileSync(groupPath, JSON.stringify(document));
  const ast = await openapiTS(new URL(`file://${groupPath}`), { alphabetize: true });
  const out = resolve(here, `../src/generated/${group}.ts`);
  writeFileSync(out, banner(group) + astToString(ast));
  console.log(`생성: ${out}`);
}
