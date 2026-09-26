/** R4 — 명시적 헤더가 있는 DOCX를 변환해 확인된 표 관계만 원문 좌표로 표시한다. */

import { expect, test } from '@playwright/test'
import { resolve } from 'node:path'

import { ROUTES } from './contract'
import { api, newAccount, signUpAndLand, verifyEmail } from './support/app'

const TABLE_FIXTURE = resolve(
  process.cwd(),
  '../backend-kotlin/infrastructure/src/testFixtures/resources/fixtures/ingest/sample_table.docx',
)

test.describe('R4 표 관계', () => {
  test.skip(
    process.env.EASYDOC_TABLE_RELATIONS_ENABLED !== 'true',
    'EASYDOC_TABLE_RELATIONS_ENABLED=true인 기존 E2E 스택에서 실행한다.',
  )

  test('명시적 DOCX 표의 헤더·셀 원문과 DOCX 내려받기를 연결한다', async ({ page }) => {
    test.setTimeout(120_000)
    const account = newAccount()
    await signUpAndLand(page, account)
    await verifyEmail(page, account)

    await page.getByRole('radio', { name: '파일 올리기' }).click()
    await page.getByLabel('바꿀 파일').setInputFiles(TABLE_FIXTURE)
    await page.getByLabel('문서 제목').fill('E2E 표 관계 확인')

    const [created] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])
    expect(created.status()).toBe(ROUTES.documentCreate.accepted)
    const { conversion_id: conversionId } = (await created.json()) as { conversion_id: string }
    const conversionPath = `/conversions/${conversionId}`

    await expect(page.getByRole('heading', { name: '쉬운 글 확인' })).toBeVisible({
      timeout: 90_000,
    })
    await expect(page.getByRole('heading', { name: '표 관계' })).toBeVisible()
    await expect(page.getByRole('columnheader', { name: '구분', exact: true })).toBeVisible()
    await expect(page.getByRole('columnheader', { name: '내용', exact: true })).toBeVisible()
    await expect(page.getByRole('cell', { name: '접수 기간' })).toBeVisible()
    await expect(page.getByRole('cell', { name: /3월 1일부터 3월 31일까지/ })).toBeVisible()
    await expect(page.getByText(/열 제목: 구분/)).toBeVisible()
    await expect(page.getByText(/열 제목: 내용/)).toBeVisible()

    const exportPath = `${conversionPath}/export?format=docx`
    const downloadPromise = page.waitForEvent('download')
    const [exportResponse, download] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(exportPath) &&
          response.request().method() === ROUTES.conversionExport.method,
      ),
      downloadPromise,
      page.getByRole('button', { name: 'DOCX로 내려받기', exact: true }).click(),
    ])
    expect(exportResponse.status()).toBe(ROUTES.conversionExport.ok)
    expect(download.suggestedFilename()).toBe('E2E 표 관계 확인-쉬운글.docx')
    expect(await download.path()).not.toBeNull()
  })
})
