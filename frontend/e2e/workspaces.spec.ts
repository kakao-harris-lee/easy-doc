/**
 * E4~E9 — 작업 공간 목록·생성·이름 변경과 그 실패 갈래.
 *
 * 케이스 ↔ 계약 대응은 계획 §3-2 표가 정본이다.
 * **E2E 대상이 아닌 것**(삭제 409 두 갈래·타인 자원 404·제어문자 이름)은 계획 §1-3 이
 * 이유와 함께 계약 테스트 층으로 보냈다 — 여기서 다시 만들지 않는다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES, emptyWorkspaceNameDetail } from './contract'
import {
  API_BASE_URL,
  INVALID_TOKEN,
  answerPrompt,
  api,
  newAccount,
  plantToken,
  selectedWorkspaceName,
  signUpAndLand,
  uniqueWorkspaceName,
  workspaceAlert,
  workspaceNames,
  workspaceSelect,
} from './support/app'
import { NetworkLog, signature } from './support/network'

interface WorkspaceListBody {
  items: { id: string; name: string }[]
}

/** `GET /workspaces` 응답 하나를 기다린다. */
function listResponse(page: import('@playwright/test').Page) {
  return page.waitForResponse(
    (response) =>
      response.url() === api(ROUTES.workspaceList.path) &&
      response.request().method() === ROUTES.workspaceList.method,
  )
}

test.describe('작업 공간', () => {
  test('E4 계정마다 자기 작업 공간만 본다', async ({ page }) => {
    // --- 계정 A ---------------------------------------------------------------
    const accountA = newAccount()
    const nameA = uniqueWorkspaceName('A')
    const listA = listResponse(page)
    await signUpAndLand(page, accountA)
    const bodyA = (await (await listA).json()) as WorkspaceListBody

    await answerPrompt(page, '새로 만들기', nameA)
    await expect(workspaceSelect(page).locator('option')).toHaveCount(2)
    expect(await workspaceNames(page)).toContain(nameA)

    // 로그아웃하면 보호 화면에서 밀려난다.
    await page.getByRole('button', { name: '로그아웃', exact: true }).click()
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()

    // --- 계정 B ---------------------------------------------------------------
    const accountB = newAccount()
    const listB = listResponse(page)
    await signUpAndLand(page, accountB)
    const bodyB = (await (await listB).json()) as WorkspaceListBody

    // 화면: A 가 만든 이름이 B 의 메뉴에 없다.
    const namesB = await workspaceNames(page)
    expect(namesB).toHaveLength(1)
    expect(namesB).not.toContain(nameA)

    // 네트워크: 두 목록의 식별자가 하나도 겹치지 않는다.
    const idsA = new Set(bodyA.items.map((item) => item.id))
    expect(bodyB.items.filter((item) => idsA.has(item.id))).toEqual([])
  })

  test('E5 작업 공간을 만들면 새 공간이 선택된다', async ({ page }) => {
    const log = new NetworkLog(page, API_BASE_URL)
    await signUpAndLand(page, newAccount())
    await expect(workspaceSelect(page).locator('option')).toHaveCount(1)

    const created = uniqueWorkspaceName()
    await answerPrompt(page, '새로 만들기', created)

    // --- 화면 -----------------------------------------------------------------
    await expect(workspaceSelect(page).locator('option')).toHaveCount(2)
    expect(await workspaceNames(page)).toContain(created)
    // 만든 뒤 그쪽으로 옮겨 간다 — 방금 만든 곳에 바로 올릴 수 있어야 한다.
    expect((await selectedWorkspaceName(page)).trim()).toBe(created)

    // --- 네트워크 -------------------------------------------------------------
    // 만들기 응답에는 문서 수도 순서도 없다 — 그래서 목록 재조회가 뒤따른다.
    const signatures = (await log.apiCalls()).map(signature)
    expect(signatures.slice(-2)).toEqual([
      `${ROUTES.workspaceCreate.method} ${ROUTES.workspaceCreate.path} ${ROUTES.workspaceCreate.created}`,
      `${ROUTES.workspaceList.method} ${ROUTES.workspaceList.path} ${ROUTES.workspaceList.ok}`,
    ])
  })

  test('E6 같은 이름으로 만들면 409 가 오고 문구가 화면에 남는다', async ({ page }) => {
    await signUpAndLand(page, newAccount())
    const name = uniqueWorkspaceName()
    await answerPrompt(page, '새로 만들기', name)
    await expect(workspaceSelect(page).locator('option')).toHaveCount(2)

    const [conflict] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.workspaceCreate.path) &&
          response.request().method() === ROUTES.workspaceCreate.method,
      ),
      answerPrompt(page, '새로 만들기', name),
    ])
    expect(conflict.status()).toBe(ROUTES.workspaceCreate.conflict)

    // **문자열을 단언하지 않는다 (RD-6).** 이 409 의 `detail` 은 계약에 예시가 없다 —
    // 계약이 침묵하는 문자열이 그대로 사용자 화면 문구인 자리다(계획 §2-4·§5 OQ-E3).
    // 잴 수 있는 것은 ⑴ 비어 있지 않다 ⑵ 서버가 준 것과 같다, 둘뿐이다.
    const alert = workspaceAlert(page)
    await expect(alert).toBeVisible()
    const shown = ((await alert.textContent()) ?? '').trim()
    expect(shown).not.toBe('')
    expect(shown).toBe(((await conflict.json()) as { detail: string }).detail)

    // 목록은 그대로다 — 실패한 생성이 화면 상태를 바꾸지 않는다.
    await expect(workspaceSelect(page).locator('option')).toHaveCount(2)
  })

  test('E7 빈 이름·공백만 이름은 422 이고 `detail` 이 문자열이다', async ({ page }) => {
    await signUpAndLand(page, newAccount())
    const expected = emptyWorkspaceNameDetail()

    for (const attempt of ['', '   ']) {
      const [rejected] = await Promise.all([
        page.waitForResponse(
          (response) =>
            response.url() === api(ROUTES.workspaceCreate.path) &&
            response.request().method() === ROUTES.workspaceCreate.method,
        ),
        answerPrompt(page, '새로 만들기', attempt),
      ])
      expect(rejected.status()).toBe(ROUTES.unprocessable)

      // 계약 `components/responses/ValidationFailed`: 요청 본문 필드의 길이·형식·빈 값
      // 규칙은 **문자열** `detail` 이다. Bean Validation 으로 구현하면 배열이 나가고
      // 문구가 영문으로 바뀌는데, 상태 코드가 같아 눈에 띄지 않는다.
      const detail = ((await rejected.json()) as { detail: unknown }).detail
      expect(Array.isArray(detail)).toBe(false)
      expect(typeof detail).toBe('string')
      // 이 문구는 계약에 **예시가 있으므로** 계약 파일에서 읽어 단언한다(계획 §3-6 축 1).
      expect(detail).toBe(expected)
      await expect(workspaceAlert(page)).toHaveText(expected)
    }

    // 아무것도 만들어지지 않았다.
    await expect(workspaceSelect(page).locator('option')).toHaveCount(1)
  })

  test('E8 이름을 바꾸면 메뉴 항목이 바뀐다', async ({ page }) => {
    const log = new NetworkLog(page, API_BASE_URL)
    await signUpAndLand(page, newAccount())
    const before = await workspaceNames(page)
    const renamed = uniqueWorkspaceName('R')

    await answerPrompt(page, '이름 바꾸기', renamed)

    // --- 화면 -----------------------------------------------------------------
    await expect(workspaceSelect(page).locator('option')).toHaveText([renamed])
    expect(await workspaceNames(page)).not.toEqual(before)

    // --- 네트워크 -------------------------------------------------------------
    // **PUT 이 아니라 PATCH 다.** 메서드를 바꾸면 `renameWorkspace` 가 그대로 깨진다.
    const calls = await log.apiCalls()
    const patch = calls.filter((entry) => entry.method === ROUTES.workspaceRename.method)
    expect(patch).toHaveLength(1)
    expect(patch[0]?.status).toBe(ROUTES.workspaceRename.ok)
    expect(calls.map(signature).at(-1)).toBe(
      `${ROUTES.workspaceList.method} ${ROUTES.workspaceList.path} ${ROUTES.workspaceList.ok}`,
    )
  })

  test('E9 X-A3 — 만료 토큰 + 빈 이름이면 422 가 아니라 401 이다', async ({ page }) => {
    await signUpAndLand(page, newAccount())
    await plantToken(page, INVALID_TOKEN)

    const [rejected] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.workspaceCreate.path) &&
          response.request().method() === ROUTES.workspaceCreate.method,
      ),
      answerPrompt(page, '새로 만들기', ''),
    ])

    // 계약 `info.description` 의 인증 우선순위 절 — 인증이 입력 검증보다 앞이다.
    // 뒤집히면 미인증자가 입력 검증 규칙을 탐색할 수 있다.
    expect(rejected.status()).toBe(ROUTES.unauthorized)
    expect(rejected.status()).not.toBe(ROUTES.unprocessable)

    // 화면도 422 문구가 아니라 로그인 화면이다.
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/login')
  })
})
