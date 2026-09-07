/**
 * E22 — 크레딧 계정 집행 켜짐 변형(402). 계획 `docs/plans/2026-09-07-credit-accounts.md`
 * §3 C2 후속.
 *
 * `credits.spec.ts`(E21)는 `easydoc.credits.enforced`를 **꺼둔** 스택에서 가입 부여·예약
 * 가시성만 잰다 — 켜면 다른 모든 e2e 스펙(문서 등록이 늘 202를 기대한다)이 402로
 * 깨지기 때문이다. 이 스펙은 그래서 **별도 스택**(`compose.e2e-enforced.yml`이
 * `easydoc.credits.enforced=true`·`EASYDOC_CREDITS_SIGNUP_GRANT=1`로 갱신한
 * `backend-api`)을 상대로 `playwright.enforced.config.ts` 하나에서만 돈다.
 *
 * 가입 부여를 1크레딧으로 좁혀서(설정값 1) "1크레딧짜리 문서 하나는 통과, 다음 등록은
 * 가용 0으로 402"라는 경계를 정확히 재현한다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

/** 짧은 붙여넣기 원문 — `ceil(chars/1000) = 1`크레딧이면 충분하다(E21과 같은 길이대). */
const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

/** 계약 `InsufficientCredits` 예시 문구(`contracts/easy-doc-v1.yaml`). */
const INSUFFICIENT_CREDITS_DETAIL = '크레딧이 부족합니다. 충전 후 다시 시도하세요.'

test.describe('크레딧 계정 (집행 켜짐)', () => {
  test('E22 가입 부여(1) → 첫 등록 202/가용0 → 두 번째 등록 402 → 화면·/usage 반영', async ({
    page,
  }) => {
    const account = newAccount()
    await signUpAndLand(page, account)
    // 이메일 인증을 먼저 마쳐야 POST /documents 가 열린다(계약 2.9.0) — E21과 같은 이유.
    await verifyEmail(page, account)

    async function registerDocument(title: string) {
      return Promise.all([
        page.waitForResponse(
          (response) =>
            response.url() === api(ROUTES.documentCreate.path) &&
            response.request().method() === ROUTES.documentCreate.method,
        ),
        (async () => {
          await page.getByLabel('문서 제목').fill(title)
          await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)
          await page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click()
        })(),
      ])
    }

    // 1) 가입 직후 가용 1 — 첫 등록은 202, 예약 직후 가용은 1 - 1 = 0
    // (`X-Credit-Balance` — 크레딧 계정 계획 §2 결정 7, 스위치와 무관하게 202에도 실린다).
    const [firstResponse] = await registerDocument('E2E 크레딧 집행 확인용 안내 1')
    expect(firstResponse.status()).toBe(ROUTES.documentCreate.accepted)
    expect(firstResponse.headers()['x-credit-balance']).toBe('0')

    // 2) 두 번째 등록 — 가용 0에 1크레딧이 필요 → 402
    // (`InsufficientCredits`: `X-Credit-Balance`·`X-Credits-Required` 헤더, 몸체는
    // `{detail}` 하나뿐 — `x-error-body-universality`).
    await page.goto('/')
    const [secondResponse] = await registerDocument('E2E 크레딧 집행 확인용 안내 2')
    expect(secondResponse.status()).toBe(402)
    expect(secondResponse.headers()['x-credits-required']).toBe('1')
    expect(secondResponse.headers()['x-credit-balance']).toBe('0')
    const body = (await secondResponse.json()) as Record<string, unknown>
    expect(Object.keys(body)).toEqual(['detail'])
    expect(body.detail).toBe(INSUFFICIENT_CREDITS_DETAIL)

    // 3) 업로드 화면 — 서버 문구 뒤에 필요·가용 크레딧을 덧붙인다
    // (`UploadPage`의 402 분기: `${message} 필요 ${required} · 가용 ${balance}`).
    await expect(page.getByRole('alert')).toHaveText(
      `${INSUFFICIENT_CREDITS_DETAIL} 필요 1 · 가용 0`,
    )

    // 4) /usage 크레딧 카드 — 가용 0, 집행이 켜져 있으므로 "지금은 집행되지 않습니다"
    // 안내는 뜨지 않는다(`CreditsCard`는 `!credits.enforced`일 때만 그 문단을 그린다).
    await page.goto('/usage')
    await expect(page.getByRole('heading', { name: '크레딧' })).toBeVisible()
    await expect(page.locator('dt:text-is("가용") + dd')).toHaveText('0')
    await expect(page.getByText('(지금은 집행되지 않습니다)')).not.toBeVisible()
  })
})
