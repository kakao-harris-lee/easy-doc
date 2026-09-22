/**
 * R7 — 검수 화면에 놓인 「그림 목록」 패널의 실제 브라우저·API·DB 수직 흐름.
 * compose.e2e.yml·run-local.sh가 이 기능을 그 스택에서만 켠다(`EASYDOC_ILLUSTRATIONS_ENABLED`).
 *
 * 카탈로그는 문서 본문과 무관하게 고정 검수 데이터(최대 10개)를 낸다 — 그래서 이 스펙은
 * R6(용어 설명)과 같은 업로드 흐름을 재사용해 검수 화면에 도달하기만 하면 된다. 유료
 * 호출은 없다(`EASYDOC_LLM_PROVIDER=fake`).
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

const FIRST_ALT_TEXT = '사람이 건물 입구로 걸어 들어가는 그림'

test.describe('R7 그림 목록', () => {
  test.skip(
    process.env.EASYDOC_ILLUSTRATIONS_ENABLED !== 'true',
    'EASYDOC_ILLUSTRATIONS_ENABLED=true인 기존 E2E 스택에서 실행한다.',
  )

  test('파일 업로드로 변환한 뒤 검수 화면에서 검수된 그림 카탈로그를 본다', async ({ page }) => {
    test.setTimeout(120_000)
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    await page.getByRole('radio', { name: '파일 올리기' }).click()
    await page.getByLabel('바꿀 파일').setInputFiles({
      name: '안내문.txt',
      mimeType: 'text/plain',
      // 카탈로그는 본문과 무관한 고정 데이터라 내용은 중요하지 않다. 다만 서버는 4어절
      // 미만 본문을 422(`too_short`, `MIN_CONVERTIBLE_WORDS`)로 거절하므로 그 하한은
      // 넘겨야 한다.
      buffer: Buffer.from('서류 제출 안내문입니다. 기한을 꼭 지켜 주세요.', 'utf-8'),
    })
    await page.getByLabel('문서 제목').fill('E2E 그림 목록')

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
    const [illustrationsResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === ROUTES.illustrationsRead.method &&
          response.url() === api(ROUTES.illustrationsRead.path),
      ),
      expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
        timeout: 90_000,
      }),
    ])
    expect(illustrationsResponse.status()).toBe(ROUTES.illustrationsRead.ok)

    // 패널 안으로 범위를 좁힌다 — section이 aria-labelledby를 가져 role=region이다.
    const panel = page.getByRole('region', { name: '그림 목록' })
    await expect(panel.getByRole('heading', { name: '그림 목록' })).toBeVisible()
    // ER-16(본문 삽입·이미지 포함 출력) 미구현을 사용자에게 명시하는 안내문.
    await expect(
      panel.getByText(/본문에 넣기와 파일 출력은 다음 단계에서 지원합니다/),
    ).toBeVisible()

    await expect(panel.getByRole('listitem')).toHaveCount(10)

    const firstImage = panel.getByRole('img', { name: FIRST_ALT_TEXT })
    await expect(firstImage).toBeVisible()
    await expect(firstImage).toHaveJSProperty('complete', true)
    const naturalWidth = await firstImage.evaluate((img: HTMLImageElement) => img.naturalWidth)
    expect(naturalWidth).toBeGreaterThan(0)
  })
})
