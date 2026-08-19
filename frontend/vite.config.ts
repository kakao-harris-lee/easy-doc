import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // 전역 주입 없이 vitest API를 명시적으로 import 한다 — 타입이 파일 안에서 닫힌다.
    globals: false,
    // 브라우저 E2E(`e2e/*.spec.ts`)는 이 스위트가 아니다. vitest 기본 include 가
    // `**/*.spec.ts` 라 그대로 두면 e2e 파일을 jsdom 에서 수집해 3개 파일이 통째로
    // 깨진다(실측). 더 중요한 것은 그 반대 방향이다 — 같은 명령에 묶이면 `frontend`
    // CI 잡이 Kotlin 서버와 Postgres 를 요구하게 되어, 계획 §3-4 (a)(순수 프런트
    // 게이트에 인프라를 넣는 안)를 뒷문으로 채택하는 것이 된다(계획 §4-3).
    // 기본 include 를 좁히지 않고 **이 디렉터리만** 뺀다 — 좁히면 src 밖에 생기는
    // 새 테스트가 조용히 수집에서 빠진다.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
