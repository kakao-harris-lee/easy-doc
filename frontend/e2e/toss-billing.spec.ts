import { execFileSync } from 'node:child_process'
import { test, expect } from '@playwright/test'
import {
  API_BASE_URL,
  newAccount,
  submitCredentials,
  verifyEmail,
  storedToken,
} from './support/app'

test.use({ trace: 'off', screenshot: 'off', video: 'off' })

test.describe('Toss test billing', () => {
  test.skip(process.env.E2E_TOSS_TEST !== '1', 'Explicit Toss test mode and test keys required')
  test('card registration and subscription approval', async ({ page, request }) => {
    test.setTimeout(240_000)
    page.setDefaultTimeout(30_000)
    const account = newAccount()
    const signup = await request.post(`${API_BASE_URL}/auth/signup`, { data: account })
    expect(signup.status()).toBe(201)
    await page.goto('/login')
    await submitCredentials(page, account, '로그인')
    await expect(page.getByRole('heading', { name: '문서 변환하기' })).toBeVisible()
    await verifyEmail(page, account)
    await page.goto('/usage')
    await page.getByRole('button', { name: 'Start 토스 테스트 카드 등록', exact: true }).click()
    await expect.poll(() => page.frames().length).toBeGreaterThan(1)
    // The hosted provider UI must load; synthetic-card interactions are added from observed labels.
    await expect
      .poll(async () =>
        (
          await Promise.all(
            page
              .frames()
              .slice(1)
              .map((f) =>
                f
                  .locator('body')
                  .innerText()
                  .catch(() => ''),
              ),
          )
        ).join(''),
      )
      .toContain('카드')
    const frame = page.frames().find((f) => f.parentFrame() !== null)!
    await frame.getByLabel('카드번호 1 ~ 4 자리', { exact: true }).fill('9410')
    await frame.getByLabel('카드번호 5 ~ 8 자리', { exact: true }).fill('8800')
    await frame.getByLabel('카드번호 9 ~ 12 자리', { exact: true }).fill('0000')
    await frame.getByLabel('카드번호 13 ~ 16 자리', { exact: true }).fill('0000')
    await frame.getByLabel('카드 유효기간', { exact: true }).fill('1230')
    // The hosted form differs by test MID. Toss's public sample MID asks for identity
    // fragments, while an automatic-billing merchant test MID can omit them.
    const birthDate = frame.getByLabel('주민등록번호 생년월일', { exact: true })
    if ((await birthDate.count()) > 0) {
      await birthDate.fill('900101')
      await frame.getByLabel('주민등록번호 성별', { exact: true }).fill('1')
    }
    await frame
      .getByRole('checkbox', { name: '[필수] 서비스 이용 약관, 개인정보 처리 동의', exact: true })
      .check()
    await frame.getByRole('button', { name: '다음', exact: true }).click()
    await expect
      .poll(
        () =>
          page.url().includes('/billing/callback') ||
          page.frames().some((current) => current.url().includes('sms-authentication')),
      )
      .toBe(true)
    const verification = page
      .frames()
      .find((current) => current.url().includes('sms-authentication'))
    if (verification) {
      // Merchant test MIDs can add Toss's sandbox identity step. The documented
      // test code is accepted without sending an SMS, so no personal data is used.
      await verification.locator('input[name="customerName"]').fill('김토스')
      await verification.getByLabel('주민등록번호 생년월일', { exact: true }).fill('900101')
      await verification.getByLabel('주민등록번호 성별', { exact: true }).fill('1')
      await verification.locator('button[aria-label="통신사 선택"]').click()
      await verification.getByText('SKT', { exact: true }).click()
      await verification.locator('input[name="phoneNumber"]').fill('01012345678')
      await verification.locator('button[aria-label="인증번호 받기"]').click()
      await verification.getByLabel('인증번호', { exact: true }).fill('000000')
      await verification.getByRole('button', { name: '확인', exact: true }).click()
    }
    await expect(page.getByRole('status')).not.toContainText(
      '카드 등록과 테스트 결제 결과를 확인하고 있습니다',
      { timeout: 90_000 },
    )
    await expect(page.getByRole('status')).toContainText('테스트 구독이 적용되었습니다', {
      timeout: 90_000,
    })
    expect(new URL(page.url()).search).toBe('')
    await page.getByRole('link', { name: '사용량으로 돌아가기' }).click()
    await expect(page.getByText('월 99,000원 · 50크레딧', { exact: true })).toBeVisible()
    const token = await storedToken(page)
    const headers = { Authorization: `Bearer ${token}` }
    const workspaces = await (await request.get(`${API_BASE_URL}/workspaces`, { headers })).json()
    const workspace = workspaces.items[0].id as string
    const base = `${API_BASE_URL}/workspaces/${workspace}/subscription`
    const read = async () => (await request.get(base, { headers })).json()
    const initial = await read()
    expect(initial.payments).toHaveLength(1)
    expect(initial.payments[0].provider).toBe('toss_test')
    const receipt = await request.get(
      `${API_BASE_URL}/workspaces/${workspace}/payments/${initial.payments[0].id}/receipt`,
      { headers },
    )
    expect(receipt.status()).toBe(200)
    expect(new URL((await receipt.json()).receipt_url).protocol).toBe('https:')
    // Only the disposable runner database is eligible for accelerated time and synthetic administrator fixtures.
    const container = process.env.E2E_PG_CONTAINER
    if (container !== 'easydoc-toss-e2e' || !/^[0-9a-f-]{36}$/.test(workspace))
      throw new Error('Isolated Toss E2E database required')
    const sql = (statement: string) =>
      execFileSync(
        'docker',
        [
          'exec',
          container,
          'psql',
          '-U',
          'postgres',
          '-d',
          'easydoc',
          '-v',
          'ON_ERROR_STOP=1',
          '-c',
          statement,
        ],
        { stdio: 'pipe' },
      )
    sql(
      `UPDATE workspace_subscriptions SET cycle_ends_at=now()-interval '1 day' WHERE workspace_id='${workspace}'`,
    )
    await expect
      .poll(async () => (await read()).payments.length, { timeout: 90_000, intervals: [1000] })
      .toBe(2)
    const renewed = await read()
    expect(renewed.subscription.status).toBe('active')
    expect(renewed.payments.every((p: { status: string }) => p.status === 'paid')).toBe(true)
    sql(
      `UPDATE users SET is_admin=true WHERE id=(SELECT user_id FROM workspaces WHERE id='${workspace}')`,
    )
    // Refund both real test approvals; replay each operation to verify idempotency at the HTTP boundary.
    for (const payment of renewed.payments as Array<{ id: string }>) {
      const path = `${API_BASE_URL}/admin/workspaces/${workspace}/payments/${payment.id}/refund`
      for (const amount of [40_000, 59_000]) {
        const data = { operation_id: crypto.randomUUID(), amount }
        expect((await request.post(path, { headers, data })).status()).toBe(200)
        expect((await request.post(path, { headers, data })).status()).toBe(200)
      }
    }
    const refunded = await read()
    expect(
      refunded.payments.every(
        (p: { status: string; refunded_amount: number }) =>
          p.status === 'refunded' && p.refunded_amount === 99_000,
      ),
    ).toBe(true)
    await page.reload()
    await page.getByRole('button', { name: '구독 갱신 중단', exact: true }).click()
    await expect(page.getByText('현재 이용 기간이 끝나면 구독이 종료됩니다.')).toBeVisible()
    expect((await read()).billing_state).toBe('revoked')
  })
})
