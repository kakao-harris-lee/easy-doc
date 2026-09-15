import { defineConfig, devices } from '@playwright/test'

// 브라우저와 API를 다른 출처로 두어 CORS 계약까지 검증한다.
const FRONTEND_ORIGIN = process.env.E2E_FRONTEND_ORIGIN ?? 'http://localhost:5173'

const API_BASE_URL = process.env.E2E_API_BASE_URL ?? 'http://localhost:8100'

const frontendPort = new URL(FRONTEND_ORIGIN).port

export default defineConfig({
  testDir: './e2e',
  // 크레딧 집행 사양은 별도 스택에서 실행한다.
  testIgnore: 'credits-enforced.spec.ts',
  fullyParallel: false,
  workers: 1,
  forbidOnly: process.env.CI === 'true',
  retries: 0,
  reporter: process.env.CI === 'true' ? [['list'], ['html', { open: 'never' }]] : [['list']],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: FRONTEND_ORIGIN,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- --port ${frontendPort} --strictPort`,
    url: FRONTEND_ORIGIN,
    env: { VITE_API_BASE_URL: API_BASE_URL },
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
