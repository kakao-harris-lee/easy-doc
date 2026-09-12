/**
 * E21 — 크레딧 계정 가시성(비집행) 수직 흐름. 계획
 * `docs/plans/2026-09-07-credit-accounts.md` §3 C2.
 *
 * `compose.e2e.yml`이 `backend-api`에 `EASYDOC_CREDITS_SIGNUP_GRANT=1000`을 얹어
 * 가입 직후 잔액 1000을 보장한다. `easydoc.credits.enforced`는 **켜지 않는다** — 켜면
 * 다른 모든 e2e 스펙(문서 등록이 늘 202를 기대한다)이 402로 깨진다. 그래서 이 스펙은
 * **집행 꺼짐** 상태에서 가입 부여·예약이 화면·헤더에 그대로 보이는지만 잰다. 집행을
 * 켠 변형(가용 부족 → 402)은 별도 조각으로 backlog에 남아 있다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

/** 짧은 붙여넣기 원문 — `ceil(chars/1000) = 1`크레딧이면 충분하다(E13과 같은 길이대). */
const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

test.describe('크레딧 계정', () => {
  test('E21 가입 부여 → 문서 등록 예약 → /usage·헤더 가시성(비집행)', async ({ page }) => {
    const account = newAccount()
    await signUpAndLand(page, account)

    // 비집행 상태는 숫자 잔액 대신 이용량 제한 없음으로 표시한다.
    // 원장 세부 값은 같은 화면 조회 응답으로 검증한다.
    const initialCredits = page.waitForResponse(
      (response) =>
        /\/workspaces\/[^/]+\/credits$/.test(response.url()) &&
        response.request().method() === 'GET',
    )
    await page.goto('/usage')
    await expect(page.getByRole('heading', { name: '이번 달 사용량' })).toBeVisible()
    await expect(page.getByText('이용량 제한 없음')).toBeVisible()
    const initial = await (await initialCredits).json()
    expect(initial.available).toBe(1000)
    expect(initial.transactions).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ reason: 'signup', kind: 'cycle_set', credits: 1000 }),
      ]),
    )

    // 2) 문서를 등록한다 — 이메일 인증을 먼저 마쳐야 POST /documents 가 열린다(계약 2.9.0).
    await page.goto('/')
    await verifyEmail(page, account)

    await page.getByLabel('문서 제목').fill('E2E 크레딧 확인용 안내')
    await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)

    const [createdResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])
    expect(createdResponse.status()).toBe(ROUTES.documentCreate.accepted)
    // 예약 직후 가용 잔액(크레딧 계정 계획 §2 결정 7) — 짧은 원문은 1크레딧만 필요하므로
    // 1000 - 1 = 999다. 집행 스위치와 무관하게 202에는 항상 실린다.
    expect(createdResponse.headers()['x-credit-balance']).toBe('999')
    // 값이 실려 있어도 CORS 노출 목록에 없으면 브라우저 JS(`client.ts`)는 이 값을 못
    // 읽는다(계약 2.22.0 ⑹ 정정 — 이전에는 이 헤더가 노출 목록에 없어 교차 출처에서
    // `null`만 받았다). Playwright의 `response.headers()`는 원시 네트워크 응답을
    // 그대로 보므로 CORS와 무관하게 항상 값을 보여준다 — 그래서 헤더 값 자체(위)만으로는
    // 이 회귀를 잡지 못하고, `Access-Control-Expose-Headers`가 실제로 이 이름을
    // 담았는지까지 함께 봐야 한다.
    expect(createdResponse.headers()['access-control-expose-headers']?.toLowerCase()).toContain(
      'x-credit-balance',
    )

    const updatedCredits = page.waitForResponse(
      (response) =>
        /\/workspaces\/[^/]+\/credits$/.test(response.url()) &&
        response.request().method() === 'GET',
    )
    await page.goto('/usage')
    await expect(page.getByText('이용량 제한 없음')).toBeVisible()
    const updated = await (await updatedCredits).json()
    expect(updated.available).toBe(999)
    expect(updated.transactions).toEqual(
      expect.arrayContaining([expect.objectContaining({ kind: 'reserve', credits: -1 })]),
    )
    await expect(page.getByRole('table')).toHaveCount(0)
  })
})
