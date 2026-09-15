/**
 * E22·E23 — 크레딧 계정 집행 켜짐 변형(402). 계획 `docs/plans/2026-09-07-credit-accounts.md`
 * §3 C2 후속.
 *
 * `credits.spec.ts`(E21)는 `easydoc.credits.enforced`를 **꺼둔** 스택에서 가입 부여·예약
 * 가시성만 잰다 — 켜면 다른 모든 e2e 스펙(문서 등록이 늘 202를 기대한다)이 402로
 * 깨지기 때문이다. 이 스펙은 그래서 **별도 스택**(`compose.e2e-enforced.yml`이
 * `easydoc.credits.enforced=true`·`EASYDOC_CREDITS_SIGNUP_GRANT=1`로 갱신한
 * `backend-api`)을 상대로 `playwright.enforced.config.ts` 하나에서만 돈다. E23은 같은
 * 스택의 stub 테스트 결제로 Start 월 50크레딧을 설정해 10크레딧 단위 차감과 소진 뒤
 * 402를 잰다. 실제 Toss나 유료 LLM은 호출하지 않는다.
 *
 * 가입 부여를 1크레딧으로 좁혀서(설정값 1) "1크레딧짜리 문서 하나는 통과, 다음 등록은
 * 가용 0으로 402"라는 경계를 정확히 재현한다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, storedToken, verifyEmail } from './support/app'

/** 짧은 붙여넣기 원문 — `ceil(chars/1000) = 1`크레딧이면 충분하다(E21과 같은 길이대). */
const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

/** 공백 포함 정확히 10,000자 — `ceil(10000/1000) = 10`크레딧이다. */
const TEN_CREDIT_TEXT = '가'.repeat(10_000)

/** 계약 `InsufficientCredits` 예시 문구(`contracts/easy-doc-v1.yaml`). */
const INSUFFICIENT_CREDITS_DETAIL = '크레딧이 부족합니다. 상위 플랜을 선택해 주세요.'

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
    await expect(page.getByRole('heading', { name: '현재 이용 기간 사용량' })).toBeVisible()
    await expect(page.locator('dt:text-is("남은 이용량") + dd')).toHaveText('0크레딧')
    await expect(page.getByText('이용량 제한 없음')).not.toBeVisible()
  })

  test('E23 Start 구독(50) → 10크레딧씩 차감 → 전량 소진 → 다음 10크레딧 요청 402', async ({
    page,
    request,
  }) => {
    test.setTimeout(120_000)
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    // 1) 월 플랜 목록에서 Start 테스트 결제를 완료하면 가입 부여 1을 더하는 대신
    // 새 월 주기의 allowance·balance를 정확히 50으로 설정한다.
    await page.goto('/usage')
    await page.getByRole('button', { name: 'Start 테스트 결제', exact: true }).click()
    const subscription = page.getByRole('region', { name: '월 구독 플랜' })
    await expect(subscription.getByRole('status')).toHaveText(
      '테스트 구독이 적용되었습니다. 실제로 청구되지 않습니다.',
    )
    await expect(subscription.getByText('Start', { exact: true })).toBeVisible()
    await expect(subscription.getByText('월 99,000원 · 50크레딧', { exact: true })).toBeVisible()

    const token = await storedToken(page)
    expect(token).not.toBeNull()
    const headers = { Authorization: `Bearer ${token}` }
    const workspacesResponse = await request.get(api('/workspaces'), { headers })
    expect(workspacesResponse.status()).toBe(200)
    const workspaces = (await workspacesResponse.json()) as { items: Array<{ id: string }> }
    const workspaceId = workspaces.items[0]?.id
    expect(workspaceId).toBeTruthy()
    const creditsPath = api(`/workspaces/${workspaceId}/credits`)

    async function readCredits() {
      const response = await request.get(creditsPath, { headers })
      expect(response.status()).toBe(200)
      return (await response.json()) as {
        allowance: number
        balance: number
        reserved: number
        available: number
        enforced: boolean
        cycle_started_at: string | null
        transactions: Array<{ kind: string; credits: number }>
      }
    }

    await expect.poll(async () => (await readCredits()).available).toBe(50)
    expect((await readCredits()).cycle_started_at).not.toBeNull()

    // 2) 첫 10,000자는 실제 업로드 화면을 통과한다. 202 헤더와 /credits가 모두
    // 50 - 10 = 40을 보여야 브라우저 클라이언트까지 이어진 차감을 증명한다.
    await page.goto('/')
    await page.getByLabel('문서 제목').fill('E2E Start 10크레딧 차감 1')
    await page.getByLabel('바꿀 글').fill(TEN_CREDIT_TEXT)
    const [firstResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])
    expect(firstResponse.status()).toBe(ROUTES.documentCreate.accepted)
    expect(firstResponse.headers()['x-credit-balance']).toBe('40')
    await expect.poll(async () => (await readCredits()).available).toBe(40)

    // 3) 같은 10크레딧 요청 네 건을 더 접수해 30 → 20 → 10 → 0 경계를 잰다.
    // HTTP를 직접 쓰는 것은 중간 네 건의 폼 조작을 반복하지 않기 위함이며, 실 Kotlin
    // API·PostgreSQL·worker를 그대로 지난다.
    for (const [index, expectedBalance] of [30, 20, 10, 0].entries()) {
      const response = await request.post(api(ROUTES.documentCreate.path), {
        headers,
        data: {
          text: TEN_CREDIT_TEXT,
          title: `E2E Start 10크레딧 차감 ${index + 2}`,
          workspace_id: workspaceId,
        },
      })
      expect(response.status()).toBe(ROUTES.documentCreate.accepted)
      expect(response.headers()['x-credit-balance']).toBe(String(expectedBalance))
    }

    // 4) 여섯 번째 10크레딧 요청은 잔액을 음수로 만들지 않고 402로 거절한다.
    const denied = await request.post(api(ROUTES.documentCreate.path), {
      headers,
      data: {
        text: TEN_CREDIT_TEXT,
        title: 'E2E Start 소진 뒤 거절',
        workspace_id: workspaceId,
      },
    })
    expect(denied.status()).toBe(402)
    expect(denied.headers()['x-credits-required']).toBe('10')
    expect(denied.headers()['x-credit-balance']).toBe('0')
    expect(await denied.json()).toEqual({ detail: INSUFFICIENT_CREDITS_DETAIL })

    // 5) worker가 다섯 예약을 소비로 확정할 때까지 기다린다. 최종 상태는 월 제공량 50,
    // 잔액·예약·가용 모두 0이며, 10크레딧 소비 거래가 정확히 다섯 건이다.
    await expect.poll(async () => (await readCredits()).reserved, { timeout: 60_000 }).toBe(0)
    const exhausted = await readCredits()
    expect(exhausted).toMatchObject({
      allowance: 50,
      balance: 0,
      reserved: 0,
      available: 0,
      enforced: true,
    })
    expect(
      exhausted.transactions.filter(
        (transaction) => transaction.kind === 'consume' && transaction.credits === -10,
      ),
    ).toHaveLength(5)

    await page.goto('/usage')
    await expect(page.getByText('50크레딧 사용')).toBeVisible()
    await expect(page.getByText(/이용 시작일부터 오늘까지/)).toBeVisible()
    await expect(page.locator('dt:text-is("남은 이용량") + dd')).toHaveText('0크레딧')
    await expect(page.getByText('이번 이용 기간의 제공량을 모두 사용했습니다.')).toBeVisible()
  })
})
