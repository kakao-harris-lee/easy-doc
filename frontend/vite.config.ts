import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // 전역 주입 없이 vitest API를 명시적으로 import 한다 — 타입이 파일 안에서 닫힌다.
    globals: false,
  },
})
