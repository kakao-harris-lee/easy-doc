/**
 * 개인정보 경고용 검출 — 계획 `docs/plans/2026-09-10-personal-data-warning.md` §4
 * 수용 기준 9(프런트). 422 + `X-Personal-Data-Kinds`를 받으면 경고와 "이대로 진행"이
 * 보이고, 누르면 같은 요청을 확인 플래그만 참으로 바꿔 재전송해 성공한다 — 붙여넣기·
 * 파일 업로드 두 경로 모두.
 *
 * **파일만 추가하고 이번 변경 단위에서는 실행하지 않는다** — 실행은 오케스트레이터가
 * 별도로 검증한다.
 */

import { expect, test } from '@playwright/test'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

/** 검증식(모듈러스 11)을 통과하는 합성 주민등록번호 — 실존 인물과 무관하다. */
const VALID_RRN = '900101-1234568'

/** 계약 `POST /documents` 422 예시 `personal_data_detected`. */
const PERSONAL_DATA_DETECTED_DETAIL = '개인정보로 보이는 내용이 있습니다. 확인 후 다시 시도하세요.'

test.describe('개인정보 경고용 검출', () => {
  test('붙여넣기 — 주민등록번호가 검출되면 경고와 "이대로 진행"이 보이고, 누르면 등록된다', async ({
    page,
  }) => {
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    await page.getByLabel('문서 제목').fill('E2E 개인정보 경고 확인용 안내')
    await page.getByLabel('바꿀 글').fill(`주민등록번호는 ${VALID_RRN} 입니다`)

    const [rejected] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])

    expect(rejected.status()).toBe(422)
    expect(rejected.headers()['x-personal-data-kinds']).toBe('rrn')
    const body = (await rejected.json()) as Record<string, unknown>
    expect(Object.keys(body)).toEqual(['detail'])
    expect(body.detail).toBe(PERSONAL_DATA_DETECTED_DETAIL)

    const warning = page.getByRole('alert').filter({ hasText: PERSONAL_DATA_DETECTED_DETAIL })
    await expect(warning).toBeVisible()
    const proceed = warning.getByRole('button', { name: '이대로 진행' })

    const [accepted] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      proceed.click(),
    ])

    expect(accepted.status()).toBe(ROUTES.documentCreate.accepted)
    await expect(page).toHaveURL(/\/conversions\//)
  })

  test('파일 업로드 — 카드번호가 검출되면 경고와 "이대로 진행"이 보이고, 누르면 등록된다', async ({
    page,
  }) => {
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    await page.getByRole('radio', { name: '파일 올리기' }).click()
    await page.getByLabel('바꿀 파일').setInputFiles({
      name: '안내문.txt',
      mimeType: 'text/plain',
      // Luhn을 통과하는 표준 테스트 카드번호(Visa 공개 테스트 번호).
      buffer: Buffer.from('카드번호는 4111-1111-1111-1111 입니다', 'utf-8'),
    })
    await page.getByLabel('문서 제목').fill('E2E 개인정보 경고 확인용 파일')

    const [rejected] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])

    expect(rejected.status()).toBe(422)
    expect(rejected.headers()['x-personal-data-kinds']).toBe('card')

    const warning = page.getByRole('alert').filter({ hasText: PERSONAL_DATA_DETECTED_DETAIL })
    await expect(warning).toBeVisible()

    const [accepted] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      warning.getByRole('button', { name: '이대로 진행' }).click(),
    ])

    expect(accepted.status()).toBe(ROUTES.documentCreate.accepted)
    await expect(page).toHaveURL(/\/conversions\//)
  })
})
