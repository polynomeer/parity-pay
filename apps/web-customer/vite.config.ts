import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // 개발 중에는 pay-api로 프록시합니다. 브라우저에서 보면 같은 오리진이라 CORS 설정이 없어도
    // 됩니다. 배포는 정적 파일 + 별도 오리진이므로 그때는 서버 CORS가 필요합니다.
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
