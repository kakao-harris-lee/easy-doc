/**
 * R6 — 검수 화면에 놓인 「용어 설명」 패널의 실제 브라우저·API·DB·worker 수직 흐름.
 * compose.e2e.yml·run-local.sh가 이 기능을 그 스택에서만 켠다(`EASYDOC_EXPLANATIONS_ENABLED`).
 *
 * fake LLM은 입력과 무관하게 고정 문장(`LocalLlmProvider.CLEAN_REPLY` = '오늘 서류를 내세요.')을
 * 돌려주고, `e2e` 프로필의 고정 응답 정의원(`ExplanationsConfiguration
 * .fakeReviewedDefinitionSource`)이 그 문장에 등장하는 표제어 「서류」 한 건을 낸다 — 그래서 이
 * 스택에서는 카드가 하나 뜨고 접기/펼치기까지 잴 수 있다. 유료 호출은 없다.
 *
 * 그 fake는 **e2e 전용**이다. 운영 경로(`dictionaryReviewedDefinitionSource`)는 커밋된 사전
 * 색인을 읽는데, 그 색인 2,177개 항목 중 검수 표시(`v`)를 가진 것이 하나도 없어 전부
 * `UNVERIFIED`로 읽히므로 **운영에서는 지금 빈 목록이 난다**(알려진 데이터 파이프라인 한계,
 * PR 「남은 한계」 참조). 이 스펙이 재는 것은 그 데이터가 실렸을 때의 화면 동작이다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

const TERM = '서류'
const EXPLANATION = '신청할 때 기관에 내는 종이 문서입니다.'

test.describe('R6 용어 설명', () => {
  test.skip(
    process.env.EASYDOC_EXPLANATIONS_ENABLED !== 'true',
    'EASYDOC_EXPLANATIONS_ENABLED=true인 기존 E2E 스택에서 실행한다.',
  )

  test('파일 업로드로 변환한 뒤 검수 화면에서 용어 설명을 펼쳐 본다', async ({ page }) => {
    test.setTimeout(120_000)
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    await page.getByRole('radio', { name: '파일 올리기' }).click()
    await page.getByLabel('바꿀 파일').setInputFiles({
      name: '안내문.txt',
      mimeType: 'text/plain',
      // 파일 내용은 변환에 쓰이지 않는다(fake LLM이 고정 문장을 돌려준다). 다만 서버는
      // 4어절 미만 본문을 422(`too_short`, `MIN_CONVERTIBLE_WORDS`)로 거절하므로 그
      // 하한은 넘겨야 한다.
      buffer: Buffer.from('서류 제출 안내문입니다. 기한을 꼭 지켜 주세요.', 'utf-8'),
    })
    await page.getByLabel('문서 제목').fill('E2E 용어 설명')

    const [created] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])
    expect(created.status()).toBe(ROUTES.documentCreate.accepted)

    // worker가 fake LLM으로 변환을 끝내고 검수 화면이 뜰 때까지 기다린다.
    const [explanationsResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === ROUTES.explanationsRead.method &&
          /\/conversions\/[^/]+\/explanations$/.test(response.url()),
      ),
      expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
        timeout: 90_000,
      }),
    ])
    expect(explanationsResponse.status()).toBe(ROUTES.explanationsRead.ok)

    // 패널 안으로 범위를 좁힌다 — 「서류」는 원문·결과 패널에도 나올 수 있어, 넓게 잡으면
    // strict mode 충돌이 난다. section 이 aria-labelledby 를 가져 role=region 이다.
    const panel = page.getByRole('region', { name: '용어 설명' })
    await expect(panel.getByRole('heading', { name: '용어 설명' })).toBeVisible()
    // AC-R6의 안내문 — 이 설명이 조건·금액·기한을 대신하지 않는다는 고지.
    await expect(
      panel.getByText(/사전에서 검수를 마친 용어를 바탕으로 한 보충 설명입니다/),
    ).toBeVisible()
    await expect(panel.getByText(TERM)).toBeVisible()

    const toggle = panel.getByRole('button', { name: '설명 더 보기' })
    await expect(toggle).toBeVisible()
    await toggle.click()

    await expect(panel.getByRole('button', { name: '설명 접기' })).toBeVisible()
    await expect(panel.getByText(EXPLANATION)).toBeVisible()

    // 「원문 보기」의 노출 여부는 fake 정의원이 근거 색인을 내는지에 달려 있으므로 여기서는
    // 단언하지 않는다 — 접기 동작만 확인한다.
    await panel.getByRole('button', { name: '설명 접기' }).click()
    await expect(toggle).toBeVisible()
    await expect(panel.getByText(EXPLANATION)).not.toBeVisible()
  })
})
