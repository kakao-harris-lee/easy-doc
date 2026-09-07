import { defineConfig, devices } from '@playwright/test'

/**
 * E22 전용 config — 크레딧 집행 켜짐 변형(402 e2e, `docs/plans/2026-09-07-credit-accounts.md`
 * §3 C2 후속).
 *
 * `playwright.config.ts`(E1~E21)와 같은 실행 방식(실 React ↔ 실 Kotlin API ↔ 실
 * PostgreSQL, 교차 출처 dev 서버, 배압 때문에 워커 1개 직렬)을 쓰지만 **다른 스택**을
 * 상대로 돈다 — `compose.e2e-enforced.yml`이 `easydoc.credits.enforced=true`·
 * `EASYDOC_CREDITS_SIGNUP_GRANT=1`로 갱신한 `backend-api`. 그 스택에서는 가입 부여
 * 1크레딧을 쓰고 나면 다음 등록이 늘 402가 되므로, 같은 백엔드를 상대로 나머지
 * 스펙(202를 기대하는 E1~E21)을 함께 돌릴 수 없다 — 그래서 `testMatch`를
 * `credits-enforced.spec.ts`(E22) 하나로 좁히고, 산출물 폴더(`outputDir`·html
 * `outputFolder`)도 기본 config의 것과 겹치지 않게 따로 둔다(CI가 두 스위트를 이어
 * 돌리며 둘 다 아티팩트로 보관한다).
 */

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
    // `--strictPort`: playwright.config.ts와 같은 이유(포트가 막히면 옆으로 옮겨가지
    // 않고 죽는다 — 옮겨 가면 출처가 달라져 CORS가 막힌다).
    command: `npm run dev -- --port ${frontendPort} --strictPort`,
    url: FRONTEND_ORIGIN,
    env: { VITE_API_BASE_URL: API_BASE_URL },
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
