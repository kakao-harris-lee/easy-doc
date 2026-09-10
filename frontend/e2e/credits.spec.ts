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

    // 1) 가입 직후 — 잔액 1000, 가용 1000, 예약 0, 거래 1건(주기 설정/가입). 가입 부여는
    // 「더하기」(grant)가 아니라 비갱신 주기를 여는 「설정」(setAllowance)이라 kind 표시는
    // 「주기 설정」이다(크레딧을 「구독 주기에 포함된 이용량」으로 바꾼 사용자 결정
    // 2026-09-10). kind 표시는 무료 체험을 넣는 방식이 바뀌면 함께 바뀔 수 있으므로,
    // 아래 단언은 reason(「가입」)을 우선한다 — 그 사실은 방식이 바뀌어도 그대로다.
    await page.goto('/usage')
    await expect(page.getByRole('heading', { name: '크레딧' })).toBeVisible()
    await expect(page.locator('dt:text-is("가용") + dd')).toHaveText('1,000')
    await expect(page.locator('dt:text-is("잔액") + dd')).toHaveText('1,000')
    await expect(page.locator('dt:text-is("예약 중") + dd')).toHaveText('0')
    // 집행이 꺼져 있다는 사실도 화면에서 확인한다 — 이 스펙이 재는 것이 비집행 변형임을
    // 스택 설정(compose.e2e.yml)뿐 아니라 화면에서도 못박는다.
    await expect(page.getByText('(지금은 집행되지 않습니다)')).toBeVisible()

    const creditsTable = page.getByRole('table', { name: /최근 크레딧 거래 내역입니다/ })
    // reason(「가입」)을 우선 단언한다 — kind(「주기 설정」)는 참고로 함께 잰다.
    await expect(creditsTable.getByText('가입')).toBeVisible()
    await expect(creditsTable.getByText('주기 설정')).toBeVisible()
    await expect(creditsTable.getByText('+1,000')).toBeVisible()

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

    // 3) 등록 뒤 — 가용은 999다. **`잔액`·`예약 중`은 여기서 단언하지 않는다** — fake
    // worker 가 폴링보다 먼저 끝나면(conversion-flow.spec.ts E13 이 이미 관측한 경쟁)
    // 예약(reserve)이 그새 소비(consume)로 넘어가 `balance=999, reserved=0`이 될 수
    // 있다. 두 상태 모두 `가용 = balance − reserved`는 999로 같으므로 그 값만 잰다
    // (reserve: (0,+1) → 999, consume: (-1,-1) → 999).
    await page.goto('/usage')
    await expect(page.locator('dt:text-is("가용") + dd')).toHaveText('999')

    // 거래 표는 append-only 원장이라(V15) 예약(reserve) 행이 지워지지 않는다 — worker가
    // 빠르게 끝나면(실측, conversion-flow.spec.ts E13과 같은 경쟁) 소비(consume) 행도
    // 곧바로 더해져 **예약·소비 두 행이 동시에** 「문서 변환」 사유로 표에 남는다. 그래서
    // `getByText('문서 변환')`처럼 표 전체에서 찾으면 strict mode 위반이다 — 종류(kind)
    // 셀로 행을 좁혀 각 행의 크레딧 셀을 잰다. `consume`도 `reserve`와 같은 크기의
    // 음수를 낸다(계약 설명, V15 리뷰).
    const updatedTable = page.getByRole('table', { name: /최근 크레딧 거래 내역입니다/ })
    const reserveRow = updatedTable.locator('tr').filter({ hasText: '예약' })
    await expect(reserveRow).toHaveCount(1)
    await expect(reserveRow.locator('td').first()).toHaveText('-1')

    // 소비 행은 worker 완주 여부에 달렸다 — 실측상 거의 항상 이미 끝나 있지만
    // (fake LLM), 혹시 아직 pending/processing 이면 이 행이 없을 수 있다. 있을 때만
    // 크기까지 잰다(강한 단언은 예약 행 하나로 충분하다).
    const consumeRow = updatedTable.locator('tr').filter({ hasText: '소비' })
    if ((await consumeRow.count()) > 0) {
      await expect(consumeRow).toHaveCount(1)
      await expect(consumeRow.locator('td').first()).toHaveText('-1')
    }
  })
})
