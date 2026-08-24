/**
 * E1·E2·E3·E12 — 가입·로그인·세션 만료·가드.
 *
 * 케이스 ↔ 계약 대응은 계획 §3-2 표가 정본이다(제거됨, git 태그
 * `pre-python-removal-20260824`에서 열람 가능: `docs/migration/_workspace/03_contract-keeper_react-e2e-plan.md`).
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import {
  API_BASE_URL,
  INVALID_TOKEN,
  TOKEN_KEY,
  answerPrompt,
  api,
  newAccount,
  plantToken,
  selectedWorkspaceName,
  signUpAndLand,
  storedToken,
  submitCredentials,
  uniqueWorkspaceName,
  workspaceNames,
  workspaceSelect,
} from './support/app'
import { NetworkLog, signature } from './support/network'

interface WorkspaceListBody {
  items: { id: string; name: string }[]
}

test.describe('인증 흐름', () => {
  test('E1 가입 → 자동 로그인 → 홈. 머리말에 기본 작업 공간 1건', async ({ page }) => {
    const log = new NetworkLog(page, API_BASE_URL)
    const account = newAccount()

    const listPromise = page.waitForResponse(
      (response) =>
        response.url() === api(ROUTES.workspaceList.path) &&
        response.request().method() === ROUTES.workspaceList.method,
    )
    await signUpAndLand(page, account)
    const listBody = (await (await listPromise).json()) as WorkspaceListBody

    // --- 화면 -----------------------------------------------------------------
    const names = await workspaceNames(page)
    expect(names).toHaveLength(1)
    expect(names[0]?.trim()).not.toBe('')
    // 가입 직후에는 기본 작업 공간 하나뿐이므로 그것이 선택돼 있어야 한다
    // (`WorkspaceProvider.pickCurrent` — 목록의 첫 항목).
    expect((await selectedWorkspaceName(page)).trim()).toBe(names[0]?.trim())

    // 화면이 그린 이름이 서버가 준 목록과 같은가 — 화면·네트워크 교차 확인.
    // 문구를 손으로 적지 않는다: 기본 작업 공간 이름은 계약이 산문으로만 적었고
    // (`paths./auth/signup.post.description`) 기계가독 키가 아니다.
    expect(listBody.items).toHaveLength(1)
    expect(names[0]?.trim()).toBe(listBody.items[0]?.name)

    // --- 네트워크 -------------------------------------------------------------
    // `AuthProvider.signUp` = signup + signIn 이고, signIn 이 login 뒤에 me 를 부른다.
    // 목록은 인증이 선 뒤 `WorkspaceProvider` 가 부른다 — 이 순서가 계약 흐름이다.
    const calls = await log.apiCalls()
    expect(calls.map(signature)).toEqual([
      `${ROUTES.signup.method} ${ROUTES.signup.path} ${ROUTES.signup.created}`,
      `${ROUTES.login.method} ${ROUTES.login.path} ${ROUTES.login.ok}`,
      `${ROUTES.me.method} ${ROUTES.me.path} ${ROUTES.me.ok}`,
      `${ROUTES.workspaceList.method} ${ROUTES.workspaceList.path} ${ROUTES.workspaceList.ok}`,
    ])
    expect(await storedToken(page)).not.toBeNull()
  })

  test('E2 로그인 자격증명 실패 — 세션 저장소를 건드리지 않는다', async ({ page }) => {
    const log = new NetworkLog(page, API_BASE_URL)
    const account = newAccount()

    await page.goto('/login')

    // ① 저장소가 빈 상태 — 계획 §3-2 E2 의 「토큰 저장소가 그대로 비어 있다」.
    const [failed] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.login.path) && response.request().method() === 'POST',
      ),
      submitCredentials(page, account, '로그인'),
    ])
    expect(failed.status()).toBe(ROUTES.unauthorized)

    const detail = ((await failed.json()) as { detail: unknown }).detail
    expect(typeof detail).toBe('string')
    // 화면 문구는 서버가 준 것 그대로여야 한다 — 문구를 손으로 적지 않는다.
    await expect(page.getByRole('alert')).toHaveText(detail as string)
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/login')
    expect(await storedToken(page)).toBeNull()

    // ② 저장소에 값이 있는 상태에서 같은 실패를 낸다.
    //
    // **이 단계가 `client.ts` 의 `token !== null` 분기를 재는 자리다.** 401 두 갈래는
    // 상태 코드도 본문 모양도 같고, 갈리는 것은 「클라이언트가 토큰을 들고 갔는가」뿐이라
    // 서버 계약 테스트로는 원리상 잴 수 없다(계획 §3-3).
    //
    // 토큰이 남아 있는데 화면이 로그인 화면인 상태는 실재한다 — 기동 시 `/auth/me` 가
    // **연결 실패**로 끝나면 `AuthProvider` 는 `anonymous` 로 내려가지만 그 실패는 401 이
    // 아니었으므로 토큰이 지워지지 않는다.
    await plantToken(page, INVALID_TOKEN)
    const [failedAgain] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.login.path) && response.request().method() === 'POST',
      ),
      page.getByRole('button', { name: '로그인', exact: true }).click(),
    ])
    expect(failedAgain.status()).toBe(ROUTES.unauthorized)
    // 인증 전 호출의 401 은 **세션 만료가 아니다.** 저장된 토큰은 그대로여야 한다.
    expect(await storedToken(page)).toBe(INVALID_TOKEN)

    // 그리고 이 401 로는 보호 화면 호출이 유발되지 않는다 — 로그인만 두 번이다.
    expect((await log.apiCalls()).map(signature)).toEqual([
      `POST ${ROUTES.login.path} ${ROUTES.unauthorized}`,
      `POST ${ROUTES.login.path} ${ROUTES.unauthorized}`,
    ])
  })

  test('E3 세션 만료 — 기동 갈래와 사용 중 갈래 둘 다 로그인 화면으로 간다', async ({ page }) => {
    // ── 갈래 A: 기동 시점. 저장된 토큰이 유효하지 않다 ─────────────────────────
    //
    // `addInitScript` 를 쓰지 않는다 — 뒤 갈래의 `goto` 마다 토큰이 다시 심겨
    // 두 번째 갈래가 성립하지 않는다.
    const bootLog = new NetworkLog(page, API_BASE_URL)
    await page.goto('/login')
    await plantToken(page, INVALID_TOKEN)
    await page.goto('/')

    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/login')
    // 401 을 받은 토큰은 더 이상 쓸모가 없다 — 즉시 버린다(`client.ts`).
    expect(await storedToken(page)).toBeNull()

    const bootCalls = await bootLog.apiCalls()
    const meCalls = bootCalls.filter((entry) => entry.path === ROUTES.me.path)
    expect(meCalls.length).toBeGreaterThan(0)
    for (const call of meCalls) {
      expect(call.status).toBe(ROUTES.unauthorized)
    }
    // 인증이 서지 않았으므로 작업 공간 목록은 부르지 않는다.
    expect(bootCalls.filter((entry) => entry.path === ROUTES.workspaceList.path)).toHaveLength(0)

    // ── 갈래 B: 사용 중 만료 ──────────────────────────────────────────────────
    //
    // 갈래 A 는 `AuthProvider` 의 `fetchMe().catch` 가 스스로 `anonymous` 로 내리므로
    // **401 핸들러 등록이 없어도 화면이 움직인다.** 핸들러가 유일한 통로인 자리는
    // 기동 이후에 만료된 경우다 — 그것이 이 갈래이고, 계획 §3-6 이 「어느 층도 잡지
    // 못한다」고 적은 변이를 실제로 잡는 자리다.
    const account = newAccount()
    await signUpAndLand(page, account)

    const liveLog = new NetworkLog(page, API_BASE_URL)
    await plantToken(page, INVALID_TOKEN)

    const [expired] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.workspaceCreate.path) &&
          response.request().method() === 'POST',
      ),
      answerPrompt(page, '새로 만들기', uniqueWorkspaceName()),
    ])
    expect(expired.status()).toBe(ROUTES.unauthorized)

    // 화면이 실제로 움직인다 — 머리말의 오류 문단이 아니라 로그인 화면이다.
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/login')
    expect(await storedToken(page)).toBeNull()
    await expect(workspaceSelect(page)).toHaveCount(0)
    expect((await liveLog.apiCalls()).map(signature)).toContain(
      `POST ${ROUTES.workspaceCreate.path} ${ROUTES.unauthorized}`,
    )
  })

  test('E12 미인증으로 보호 화면에 직접 들어가면 보호 API 호출이 아예 나가지 않는다', async ({
    page,
  }) => {
    const log = new NetworkLog(page, API_BASE_URL)

    await page.goto('/history')

    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/login')
    expect(await page.evaluate((key) => window.localStorage.getItem(key), TOKEN_KEY)).toBeNull()
    // 서버와 무관한 스모크다 — `RequireAuth` 가 판단을 서버에 미루지 않는다.
    expect(log.apiRequests()).toEqual([])
    expect(await log.api()).toEqual([])
  })
})
