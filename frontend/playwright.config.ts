import { defineConfig, devices } from '@playwright/test'

/**
 * Phase 3 브라우저 E2E — 실 React ↔ 실 Kotlin API ↔ 실 PostgreSQL.
 *
 * 계획 정본(제거됨, git 태그 `pre-python-removal-20260824`에서 열람 가능):
 * `docs/migration/_workspace/03_contract-keeper_react-e2e-plan.md`.
 * 스텁·목·MSW 를 쓰지 않는다. 기존 Vitest 스위트(`npm run test`)와 **별 스크립트**다 —
 * 같은 명령에 묶으면 `frontend` CI 잡이 서버를 요구하게 되어 계획 §3-4 (a) 를 뒷문으로
 * 채택하는 것이 된다(계획 §4-3).
 *
 * ## 교차 출처를 유지한다 (계획 §1-3)
 *
 * nginx `/api` 프록시를 쓰지 않는다. 프록시를 먼저 붙이면 CORS 계약
 * (`contracts/easy-doc-v1.yaml` `x-cors`)이 한 번도 실행되지 않은 채 Phase 6 에서 처음
 * 드러난다. 그래서 브라우저는 [FRONTEND_ORIGIN] 을 보고 API 는 [API_BASE_URL] 로 나간다.
 *
 * ## 왜 `vite preview` 가 아니라 dev 서버인가
 *
 * Kotlin 기본 허용 origin(`easydoc.cors-origins`)이 Vite dev 서버 포트와 같다. preview 는
 * 포트가 달라 프리플라이트가 막히고, 그것을 통과시키려면 허용 origin 을 환경변수로
 * 넣어야 한다 — 계약 기본값을 실행하지 않게 된다(계획 §3-4).
 *
 * ## 왜 워커가 하나인가
 *
 * 서버가 Argon2 동시 계산을 `easydoc.auth.max-concurrent-hashes`(기본 4)로 묶고 대기
 * 상한을 250ms 로 둔다. 병렬 워커가 가입·로그인을 겹쳐 내면 **배압 500** 이 나서
 * 테스트가 서버 과부하로 빨개진다 — 그것은 이 스위트가 재려는 결함이 아니다.
 */

/** 브라우저가 보는 출처. 계약 `x-cors.allow_origins` 에 이 값이 있어야 한다(E11 이 단언한다). */
const FRONTEND_ORIGIN = process.env.E2E_FRONTEND_ORIGIN ?? 'http://localhost:5173'

/** Kotlin API 출처. compose 의 `backend-api`와 같은 포트를 기본값으로 둔다. */
const API_BASE_URL = process.env.E2E_API_BASE_URL ?? 'http://localhost:8100'

const frontendPort = new URL(FRONTEND_ORIGIN).port

export default defineConfig({
  testDir: './e2e',
  // 케이스마다 계정을 새로 만들지만(계획 §4-4), 서버 배압 때문에 직렬로 돈다.
  fullyParallel: false,
  workers: 1,
  forbidOnly: process.env.CI === 'true',
  retries: 0,
  reporter: process.env.CI === 'true' ? [['list'], ['html', { open: 'never' }]] : [['list']],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: FRONTEND_ORIGIN,
    // 실패한 실행만 추적을 남긴다. 추적에는 화면 스냅샷이 들어가므로 합성 계정만
    // 쓰는 이 스위트에서만 안전하다(실 사용자 데이터로 돌리지 않는다).
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // `--strictPort`: 포트가 이미 쓰이면 조용히 옆 포트로 옮기지 않고 죽는다. 옮겨 가면
    // 출처가 달라져 CORS 가 막히고, 원인이 "테스트 실패"로 보인다.
    command: `npm run dev -- --port ${frontendPort} --strictPort`,
    url: FRONTEND_ORIGIN,
    env: { VITE_API_BASE_URL: API_BASE_URL },
    // 재사용하지 않는다 — 이미 떠 있는 dev 서버는 다른 `VITE_API_BASE_URL` 로 떴을 수
    // 있고, 그러면 이 스위트가 무엇을 상대로 돌았는지 알 수 없다.
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
