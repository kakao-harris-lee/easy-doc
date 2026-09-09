/**
 * E-가입크레딧한번 — 가입 → 탈퇴 → 같은 이메일 재가입 → 인증 → 크레딧 0과 안내 문구 확인.
 *
 * 정본은 `docs/plans/2026-09-09-account-deletion.md` §9 수용 기준 9(가입 크레딧 후속
 * 「가입 크레딧은 계정당 한 번」). 원장(`signup_grant_records`) 자체는 Kotlin
 * `SignupGrantOnceTest`(실 PostgreSQL)가 이미 잰다 — 여기서는 **실 브라우저·실 API**를
 * 한 줄로 이어 재가입한 사용자가 실제로 크레딧을 받지 못하고, 이메일 인증을 마친 뒤
 * 그 사실을 화면에서 확인할 수 있는지만 잰다.
 *
 * `compose.e2e.yml`이 `backend-api`에 `EASYDOC_CREDITS_SIGNUP_GRANT=1000`·
 * `EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER`를 얹어 가입 부여와 그 자기점검을 함께
 * 만족시킨다 — `credits.spec.ts`(E21)와 같은 전제.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { newAccount, signUpAndLand, verifyEmail } from './support/app'

test.describe('가입 크레딧은 이메일당 한 번', () => {
  test('E-가입크레딧한번 가입 → 탈퇴 → 같은 이메일 재가입 → 인증 → 크레딧 0 + 안내 문구', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const account = newAccount()

    // 1) 처음 가입 — 가입 부여를 정상 수령한다(E21과 같은 전제).
    await signUpAndLand(page, account)
    await page.goto('/usage')
    await expect(page.locator('dt:text-is("가용") + dd')).toHaveText('1,000')
    await expect(
      page.getByText(
        '이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.',
      ),
    ).not.toBeVisible()

    // 2) 탈퇴한다 — `account-deletion.spec.ts`와 같은 절차(비밀번호 계정이라 비밀번호 필수).
    await page.goto('/')
    await page.getByRole('button', { name: '계정 메뉴' }).click()
    await page.getByRole('link', { name: '계정 설정' }).click()
    await expect(page.getByRole('heading', { name: '계정 설정' })).toBeVisible()

    await page.getByRole('button', { name: '회원 탈퇴', exact: true }).click()
    await page.getByLabel('비밀번호').fill(account.password)
    await page.getByLabel('확인 문구').fill('탈퇴합니다')

    const [deleteResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().endsWith(ROUTES.deleteAccount.path) &&
          response.request().method() === ROUTES.deleteAccount.method,
      ),
      page.getByRole('button', { name: '계정을 영구히 삭제합니다' }).click(),
    ])
    expect(deleteResponse.status()).toBe(ROUTES.deleteAccount.noContent)
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()

    // 3) 같은 이메일로 재가입한다 — 응답 자체는 아직 아무 안내도 싣지 않는다(이메일
    // 인증 전에는 signup_grant_skipped 가 항상 거짓이다, §7 결정 5 — 존재 은닉).
    // `signUpAndLand`로 착지(작업 공간 메뉴가 뜰 때까지)를 기다린 뒤에만 `/usage`로
    // 옮긴다 — 재가입 응답이 토큰을 저장하기 전에 이동하면 인증이 없어 로그인 화면으로
    // 튕기고, 「가용」 요소 자체가 없어 아래 단언이 타임아웃으로 실패한다(리뷰 2026-09-10).
    await signUpAndLand(page, account)
    await page.goto('/usage')
    await expect(page.locator('dt:text-is("가용") + dd')).toHaveText('0')
    await expect(
      page.getByText(
        '이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.',
      ),
    ).not.toBeVisible()

    // 4) 이메일 인증을 마친 뒤에야 안내가 보인다.
    await page.goto('/')
    await verifyEmail(page, account)
    await page.goto('/usage')
    await expect(page.locator('dt:text-is("가용") + dd')).toHaveText('0')
    await expect(
      page.getByText(
        '이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.',
      ),
    ).toBeVisible()

    const creditsTable = page.getByRole('table', { name: /최근 크레딧 거래 내역입니다/ })
    await expect(creditsTable.getByText('아직 거래가 없습니다.')).toBeVisible()
  })
})
