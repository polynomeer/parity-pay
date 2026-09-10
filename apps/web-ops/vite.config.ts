import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // 고객 앱과 **다른 포트**입니다. 배포에서도 다른 오리진에 둡니다. 운영 콘솔의 코드가 고객
  // 브라우저로 내려가면 안 되기 때문입니다. 근거: docs/14-frontend-design.md §2
  server: {
    port: 5174,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
  test: { environment: "jsdom", globals: true, setupFiles: ["./src/test/setup.ts"] },
});
