/**
 * E-회원탈퇴 — 가입 → 문서 1건 변환 → 회원 탈퇴 → 로그인 화면 도착 → 같은 이메일 재가입.
 *
 * 정본은 `docs/plans/2026-09-09-account-deletion.md` §4 수용 기준 10. 파기 범위(행
 * 수·CASCADE·`llm_calls` SET NULL)는 Kotlin `JdbcAccountDeletionRepositoryTest`(실
 * PostgreSQL)가 이미 고정했다 — 여기서는 **실 브라우저·실 API·실 worker**를 한 줄로
 * 이어 화면이 실제로 로그인 화면에 도착하고, 파기된 이메일로 다시 가입할 수 있는지만
 * 잰다(§2 결정 8 — 재가입은 막지 않는다).
 *
 * `run-local.sh`가 worker를 fake LLM으로 띄운다 — `conversion-flow.spec.ts`와 같은 전제.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import {
  newAccount,
  signUpAndLand,
  submitCredentials,
  verifyEmail,
  workspaceSelect,
} from './support/app'

/** 붙여넣기 원문. 변환이 끝나는지만 확인하면 되므로 짧게 둔다(`conversion-flow.spec.ts`와 같은 이유). */
const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

test.describe('회원 탈퇴', () => {
  test('E-회원탈퇴 가입 → 문서 변환 → 탈퇴 → 로그인 화면 → 같은 이메일 재가입', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const account = newAccount()
    await signUpAndLand(page, account)
    // 이메일/비밀번호 계정은 인증을 마쳐야 문서 등록(`POST /documents`)이 열린다(계약 2.9.0).
    await verifyEmail(page, account)

    await page.getByLabel('문서 제목').fill('E2E 탈퇴 확인용 문서')
    await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)
    await page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click()

    // worker가 fake LLM으로 끝낼 때까지 기다린다 — "문서 1건 변환"의 정본 신호다.
    await expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
      timeout: 90_000,
    })

    // 계정 메뉴 → 계정 설정 → 회원 탈퇴.
    await page.getByRole('button', { name: '계정 메뉴' }).click()
    await page.getByRole('link', { name: '계정 설정' }).click()
    await expect(page.getByRole('heading', { name: '계정 설정' })).toBeVisible()

    await page.getByRole('button', { name: '회원 탈퇴', exact: true }).click()
    // 이메일/비밀번호 계정이라 비밀번호가 필수다(`readMe.has_password: true`).
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

    // 세션이 정리되고 로그인 화면에 도착한다 — 별도 이동 없이 `RequireAuth`가 돌려보낸다.
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()

    // 같은 이메일로 다시 가입할 수 있다(§2 결정 8 — 재가입은 막지 않는다).
    await page.goto('/signup')
    await submitCredentials(page, account, '가입하기')
    await expect(workspaceSelect(page)).toBeVisible()
  })
})
