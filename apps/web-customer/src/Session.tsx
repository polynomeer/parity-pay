/**
 * 앱이 뜰 때 세션을 되살립니다.
 *
 * 액세스 토큰은 메모리에만 있어 새로고침마다 사라집니다(ADR-010 후속). 보호된 경로가 그 순간
 * 토큰이 없다고 로그인으로 보내면 새로고침할 때마다 로그인 화면이 깜빡입니다. 그래서 쿠키로
 * 재발급을 시도하는 동안은 아무 경로도 판정하지 않습니다.
 */
import { useEffect, useState, type ReactNode } from "react";
import { auth } from "./api";

export function Session({ children }: { children: ReactNode }) {
  const [restored, setRestored] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // 실패는 로그아웃 상태이지 오류가 아닙니다. 어느 쪽이든 판정을 시작합니다.
    void auth.restore().finally(() => {
      if (!cancelled) {
        setRestored(true);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!restored) {
    return <p role="status">세션을 확인하고 있습니다…</p>;
  }
  return <>{children}</>;
}
