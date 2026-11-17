import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 빌드 결과는 예제서버의 static 폴더로 — Spring Boot가 그대로 내려 준다 (8080 하나로 실행)
// 개발 중(npm run dev, 5173)에는 /api 요청을 8080으로 넘겨서 세션 쿠키가 그대로 붙는다
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
