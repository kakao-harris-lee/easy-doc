/**
 * E20 — 비밀번호 재설정: 요청 → 가짜 메일에서 코드 읽기 → 확인 → 로그인 상태.
 *
 * `__e2e/mail/latest` 진단 엔드포인트로 실제 메일 서버 없이 코드를 읽는다
 * (`support/app.ts`의 `latestMailCode` — 이메일 인증 코드와 같은 헬퍼를 공유한다).
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import {
  latestMailCode,
  newAccount,
  signOut,
  signUpAndLand,
  storedToken,
  workspaceSelect,
} from './support/app'

test.describe('비밀번호 재설정', () => {
  test('E20 요청 → 코드 확인 → 로그인 상태로 홈에 도착한다', async ({ page }) => {
    const account = newAccount()
    await signUpAndLand(page, account)
    await signOut(page)
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()

    await page.getByRole('link', { name: '비밀번호를 잊으셨나요?' }).click()
    await expect(page.getByRole('heading', { name: '비밀번호 재설정' })).toBeVisible()

    await page.getByLabel('이메일').fill(account.email)
    const [requestResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().endsWith(ROUTES.passwordResetRequest.path) &&
          response.request().method() === ROUTES.passwordResetRequest.method,
      ),
      page.getByRole('button', { name: '재설정 코드 받기' }).click(),
    ])
    expect(requestResponse.status()).toBe(ROUTES.passwordResetRequest.accepted)
    await expect(page.getByRole('status')).toHaveText('코드를 보냈습니다. 메일함을 확인하세요.')

    const code = await latestMailCode(page, account.email)
    const newPassword = 'e2e-new-synthetic-password'

    await page.getByLabel('인증 코드').fill(code)
    await page.getByLabel('새 비밀번호', { exact: true }).fill(newPassword)
    await page.getByLabel('새 비밀번호 확인').fill(newPassword)

    const [confirmResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().endsWith(ROUTES.passwordResetConfirm.path) &&
          response.request().method() === ROUTES.passwordResetConfirm.method,
      ),
      page.getByRole('button', { name: '비밀번호 재설정' }).click(),
    ])
    expect(confirmResponse.status()).toBe(ROUTES.passwordResetConfirm.ok)

    // 재설정 확인 성공은 로그인과 같은 방식으로 토큰을 저장하고 홈으로 보낸다.
    await expect(workspaceSelect(page)).toBeVisible()
    expect(await storedToken(page)).not.toBeNull()
  })
})
