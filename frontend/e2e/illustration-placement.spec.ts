/**
 * ER-16 — 검수 화면에서 검수된 그림을 본문 줄에 배치하고 웹 미리보기로 확인한다.
 * illustrations.spec.ts(R7 그림 목록)와 같은 이유로 같은 E2E 스택에서만 돈다
 * (`EASYDOC_ILLUSTRATIONS_ENABLED`). 업로드부터 검수 화면 도달까지는 그 스펙의 흐름을
 * 그대로 재사용한다 — 카탈로그처럼 이 기능도 본문과 무관하게 고정 검수 데이터를 쓴다.
 *
 * fake LLM(`LocalLlmProvider`)은 입력과 무관하게 고정 한 줄(`오늘 서류를 내세요.`)을
 * 돌려준다 — 그래서 최초 화면의 select는 1개뿐이다. 본문을 두 줄로 고쳐 저장하면
 * content_revision이 올라 방금 저장한 배치가 stale이 되는 것까지 이 스펙에서 함께 잰다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

const FIRST_LINE_ALT_TEXT = '사람이 건물 입구로 걸어 들어가는 그림'

test.describe('ER-16 그림 배치', () => {
  test.skip(
    process.env.EASYDOC_ILLUSTRATIONS_ENABLED !== 'true',
    'EASYDOC_ILLUSTRATIONS_ENABLED=true인 기존 E2E 스택에서 실행한다.',
  )

  test('그림을 배치해 미리보기로 보고, 본문을 다시 저장하면 stale로 숨긴다', async ({ page }) => {
    test.setTimeout(120_000)
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    await page.getByRole('radio', { name: '파일 올리기' }).click()
    await page.getByLabel('바꿀 파일').setInputFiles({
      name: '안내문.txt',
      mimeType: 'text/plain',
      // 그림 배치는 카탈로그처럼 본문과 무관한 고정 검수 데이터를 쓴다. 4어절 미만
      // 본문은 422(too_short)로 거절되므로 그 하한만 넘긴다.
      buffer: Buffer.from('서류 제출 안내문입니다. 기한을 꼭 지켜 주세요.', 'utf-8'),
    })
    await page.getByLabel('문서 제목').fill('E2E 그림 배치')

    const [created] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])
    expect(created.status()).toBe(ROUTES.documentCreate.accepted)

    const createdBody = (await created.json()) as { conversion_id: string }
    const conversionPath = `/conversions/${createdBody.conversion_id}`
    const placementsPath = `${conversionPath}/illustration-placements`

    // worker가 fake LLM으로 변환을 끝내고 검수 화면이 뜨면, 그림 배치 패널이 저장된
    // 배치(처음에는 없음)를 조회한다.
    const [placementsResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(placementsPath) &&
          response.request().method() === ROUTES.illustrationPlacementsRead.method,
      ),
      expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
        timeout: 90_000,
      }),
    ])
    expect(placementsResponse.status()).toBe(ROUTES.illustrationPlacementsRead.ok)

    const panel = page.getByRole('region', { name: '그림 배치' })
    await expect(panel.getByRole('heading', { name: '그림 배치' })).toBeVisible()

    // fake LLM 본문은 한 줄이라 첫 줄 select 하나만 있다.
    const firstLineSelect = panel.getByRole('combobox', { name: '1번째 줄 그림' })
    await firstLineSelect.selectOption({ label: '기관 방문' })

    const [saveResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(placementsPath) &&
          response.request().method() === ROUTES.illustrationPlacementsWrite.method,
      ),
      panel.getByRole('button', { name: '그림 배치 저장' }).click(),
    ])
    expect(saveResponse.status()).toBe(ROUTES.illustrationPlacementsWrite.ok)

    await expect(panel.getByRole('img', { name: FIRST_LINE_ALT_TEXT })).toBeVisible()
    // ER-16 AC-R7-b — 그림은 파일 출력에 담기지 않는다는 사실을 내려받기 옆에서 명시한다.
    await expect(
      page.getByText('배치한 그림은 파일에 들어가지 않습니다', { exact: false }),
    ).toBeVisible()

    // 본문을 두 줄로 고쳐 저장하면 content_revision이 올라, 방금 저장한 배치는 낡은 본문
    // 기준이 된다 — 화면은 그림을 숨기고 다시 확인하라고 안내해야 한다(stale).
    const editor = page.getByLabel(/^(쉬운 글 단위 1, .+|쉬운 글 결과 \(고칠 수 있습니다\))$/)
    await editor.fill('첫째 줄을 고쳤습니다.\n둘째 줄을 새로 넣었습니다.')

    const [bodySavedResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(conversionPath) &&
          response.request().method() === ROUTES.conversionReview.method,
      ),
      page.getByRole('button', { name: '검수 내용 저장', exact: true }).click(),
    ])
    expect(bodySavedResponse.status()).toBe(ROUTES.conversionReview.ok)

    await expect(panel.getByText('현재 본문과 다름')).toBeVisible()
    await expect(panel.getByRole('img')).toHaveCount(0)
  })
})
