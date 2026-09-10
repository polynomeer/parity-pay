/**
 * TanStack Query 기본값.
 *
 * **뮤테이션 재시도를 켜지 않습니다.** 라이브러리가 대신 재시도하면 화면이 만든 멱등 키를
 * 그대로 쓰는지 보장할 수 없습니다. 쓰기의 재시도는 `ApiClient`와 화면의 상태 기계가 맡습니다.
 * 근거: docs/14-frontend-design.md §7
 */
import { QueryClient } from "@tanstack/react-query";

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      // 읽기는 부작용이 없으므로 재시도해도 안전합니다.
      queries: { retry: 2, staleTime: 5_000 },
      // 쓰기는 아닙니다. 0이 아니라 false로 두어 의도를 분명히 합니다.
      mutations: { retry: false },
    },
  });
}
