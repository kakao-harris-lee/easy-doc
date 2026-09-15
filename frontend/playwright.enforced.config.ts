import { defineConfig, devices } from '@playwright/test'

const FRONTEND_ORIGIN = process.env.E2E_FRONTEND_ORIGIN ?? 'http://localhost:5173'
const API_BASE_URL = process.env.E2E_API_BASE_URL ?? 'http://localhost:8100'
const frontendPort = new URL(FRONTEND_ORIGIN).port

export default defineConfig({
  testDir: './e2e',
  testMatch: 'credits-enforced.spec.ts',
  outputDir: './test-results-enforced',
  fullyParallel: false,
  workers: 1,
  forbidOnly: process.env.CI === 'true',
  retries: 0,
  reporter:
    process.env.CI === 'true'
      ? [['list'], ['html', { outputFolder: 'playwright-report-enforced', open: 'never' }]]
      : [['list']],
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
