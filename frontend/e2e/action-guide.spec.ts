/** 행동 안내 capability와 무관하게 쉬운 글 편집·저장·출력에 집중하는 서비스 화면 회귀. */
import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { ConversionResponse } from '../src/api/types'
import { ROUTES } from './contract'
import {
  API_BASE_URL,
  api,
  newAccount,
  signUpAndLand,
  storedToken,
  verifyEmail,
} from './support/app'
import { NetworkLog } from './support/network'

const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

test('쉬운 글 결과 — 행동 안내 capability가 켜져도 안내 요청 없이 본문 수정·저장·출력', async ({
  page,
}) => {
  test.setTimeout(150_000)
  const log = new NetworkLog(page, API_BASE_URL)
  const account = newAccount()
  await signUpAndLand(page, account)
  await verifyEmail(page, account)
  await page.getByLabel('문서 제목').fill(`E2E 쉬운 글 ${crypto.randomUUID().slice(0, 8)}`)
  await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)
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
  await expect(page.getByRole('heading', { name: '쉬운 글 확인', exact: true })).toBeVisible({
    timeout: 90_000,
  })
  const token = await storedToken(page)
  const conversionPath = `/conversions/${conversionId}`
  const loaded = await page.request.get(api(conversionPath), {
    headers: { Authorization: `Bearer ${token}` },
  })
  const conversion = (await loaded.json()) as ConversionResponse
  expect(conversion.review_capabilities?.action_guide).toBe(true)
  expect(conversion.review_capabilities?.action_guide_workflow).toBe(true)
  expect(conversion.review_capabilities?.action_guide_workflow_read).toBe(true)
  await expect(page.getByRole('tablist', { name: '작업 선택' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '행동 안내' })).toHaveCount(0)
  await expect(page.getByText('행동 확인과 문서 보완', { exact: true })).toHaveCount(0)
  const editor = page.getByRole('textbox', { name: '쉬운 글 결과 (고칠 수 있습니다)' })
  const edited = `${await editor.inputValue()}\n궁금한 내용은 방문 전에 확인하세요.`
  await editor.fill(edited)
  await expect(page.getByText('저장 안 됨', { exact: true })).toBeVisible()
  if (conversion.review_capabilities?.review_history) {
    await page.getByRole('button', { name: '수정 기록 보기', exact: true }).click()
    await expect(editor).toBeVisible()
    await expect(editor).toHaveValue(edited)
    await page.getByRole('button', { name: '수정 기록 접기', exact: true }).click()
  }
  const [saved] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url() === api(conversionPath) &&
        response.request().method() === ROUTES.conversionReview.method,
    ),
    page.getByRole('button', { name: '검수 내용 저장', exact: true }).click(),
  ])
  expect(saved.status()).toBe(ROUTES.conversionReview.ok)
  await expect(page.getByText('검수 내용을 저장했습니다.', { exact: true })).toBeVisible()
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'TXT로 내려받기', exact: true }).click(),
  ])
  const path = await download.path()
  if (!path) throw new Error('저장한 쉬운 글 다운로드 파일 없음')
  expect(await readFile(path, 'utf8')).toContain(edited)
  await page.reload()
  await expect(editor).toHaveValue(edited)
  await expect(page.getByRole('tab', { name: '행동 안내' })).toHaveCount(0)
  expect(
    log.apiRequests().filter((request) => new URL(request.url).pathname.includes('/action-guide')),
  ).toEqual([])
})
