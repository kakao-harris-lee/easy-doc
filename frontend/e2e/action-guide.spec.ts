/**
 * ER-07 — 실제 브라우저·API·DB·worker를 거치는 별도 행동 안내문 흐름.
 * compose.e2e.yml은 행동 안내 기능을 명시적으로 켜고 worker에 action-guide-fake
 * 프로필을 추가한다. 이 스펙은 유료 LLM을 호출하지 않는다.
 */

import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'

import type { ActionGuideResource } from '../src/api/types'
import { ROUTES } from './contract'
import {
  API_BASE_URL,
  api,
  newAccount,
  signUpAndLand,
  storedToken,
  verifyEmail,
} from './support/app'
import { NetworkLog, signature } from './support/network'

const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

interface CreatedDocument {
  conversion_id: string
}

interface CreatedJob {
  job_id: string
  request_id: string
  reserved_credits: number
  status: string
}

/** 각 시나리오가 다른 계정·문서를 쓰므로 테스트 간 작업·이용량 상태를 공유하지 않는다. */
async function openConvertedDocument(page: Page): Promise<string> {
  const account = newAccount()
  await signUpAndLand(page, account)
  await verifyEmail(page, account)
  await page.getByLabel('문서 제목').fill(`E2E 행동 안내 ${crypto.randomUUID().slice(0, 8)}`)
  await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)

  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.url() === api(ROUTES.documentCreate.path) &&
        candidate.request().method() === ROUTES.documentCreate.method,
    ),
    page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
  ])
  expect(response.status()).toBe(ROUTES.documentCreate.accepted)
  const { conversion_id: conversionId } = (await response.json()) as CreatedDocument
  await expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
    timeout: 90_000,
  })
  return conversionId
}

test.describe('ER-07 행동 안내', () => {
  test('생성 전 확인 → 재방문 후 후보 적용 → 담당자 확인 → TXT 다운로드', async ({ page }) => {
    test.setTimeout(150_000)

    const log = new NetworkLog(page, API_BASE_URL)
    const conversionId = await openConvertedDocument(page)
    const guidePath = `/conversions/${conversionId}/action-guide`
    const jobsPath = `${guidePath}-jobs`

    await expect(page.getByRole('tab', { name: '행동 안내' })).toBeVisible()
    await page.getByRole('tab', { name: '행동 안내' }).click()
    await expect(page.getByRole('button', { name: '안내문 만들기', exact: true })).toBeVisible()
    await expect(page.getByText(/필요 이용량 .*크레딧/)).toBeVisible()
    await expect(page.getByText(/남은 이용량 .*크레딧/)).toBeVisible()
    expect(
      log
        .apiRequests()
        .filter((request) => request.method === 'POST' && request.url === api(jobsPath)),
    ).toHaveLength(0)

    const createTrigger = page.getByRole('button', { name: '안내문 만들기', exact: true })
    const createConfirm = page.getByRole('button', { name: /크레딧으로 안내문 만들기/ })
    await createTrigger.click()
    await expect(createConfirm).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(createTrigger).toBeFocused()
    await createTrigger.click()
    await expect(createConfirm).toBeFocused()
    // 확인 전에는 크레딧 예약이나 worker 작업이 시작되면 안 된다.
    expect(
      log
        .apiRequests()
        .filter((request) => request.method === 'POST' && request.url === api(jobsPath)),
    ).toHaveLength(0)

    const [createdResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(jobsPath) &&
          response.request().method() === ROUTES.actionGuideJobCreate.method,
      ),
      createConfirm.click(),
    ])
    expect(createdResponse.status(), await createdResponse.text()).toBe(
      ROUTES.actionGuideJobCreate.accepted,
    )
    const created = (await createdResponse.json()) as CreatedJob
    expect(created.job_id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(created.request_id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(created.reserved_credits).toBeGreaterThan(0)

    // 브라우저 저장소의 임시 작업 키에 의존하지 않고, 서버가 반환하는 최신 job으로 복구한다.
    const callsBeforeReload = (await log.apiCalls()).length
    await page.reload()
    await expect(page.getByRole('tab', { name: '행동 안내' })).toBeVisible()
    await page.getByRole('tab', { name: '행동 안내' }).click()
    await expect(page.getByRole('button', { name: '초안 사용' })).toBeVisible({
      timeout: 90_000,
    })
    const preview = page.getByRole('region', { name: '행동 안내 보조자료 미리보기' })
    await expect(preview).toBeVisible()
    await expect(preview.getByText(/문서 전체의 내용을 담고 있지는 않습니다/)).toBeVisible()
    await expect(preview.getByText('원문에 안내 없음', { exact: true })).toHaveCount(6)
    const recoveredCalls = (await log.apiCalls()).slice(callsBeforeReload).map(signature)
    expect(recoveredCalls).toContain(
      `${ROUTES.actionGuideRead.method} ${guidePath} ${ROUTES.actionGuideRead.ok}`,
    )
    expect(recoveredCalls).toContain(
      `${ROUTES.actionGuideJobs.method} ${jobsPath} ${ROUTES.actionGuideJobs.ok}`,
    )
    expect(recoveredCalls).toContain(
      `${ROUTES.actionGuideJobRead.method} ${jobsPath}/${created.job_id} ${ROUTES.actionGuideJobRead.ok}`,
    )

    const [appliedResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideSave.method,
      ),
      page.getByRole('button', { name: '초안 사용' }).click(),
    ])
    expect(appliedResponse.status()).toBe(ROUTES.actionGuideSave.ok)
    expect((await appliedResponse.json()) as { status: string }).toMatchObject({ status: 'draft' })

    // fake 후보는 원문에 없는 여섯 섹션을 명시한다. 비어 있는 항목처럼 숨기면 안 된다.
    await expect(page.getByText('원문에 안내 없음').first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'TXT로 내려받기' })).toBeDisabled()

    // fake 후보 자체에는 항목이 없다. 사용자가 원문을 보고 근거를 지정해 항목을
    // 추가할 수 있어야 하며, 수정 직후에는 담당자 확인 전에 초안부터 저장한다.
    await page
      .getByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' })
      .selectOption('available')
    await page
      .getByRole('group', { name: '신청할 수 있는 사람' })
      .getByRole('button', { name: '항목 추가' })
      .click()
    await page.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }).fill(SOURCE_TEXT)
    const caution = '납부하려는 경우에만 해당합니다.'
    await page.getByRole('textbox', { name: '신청할 수 있는 사람 1번 주의할 점' }).fill(caution)
    await page
      .getByRole('combobox', { name: '신청할 수 있는 사람 1번 원문 근거' })
      .selectOption('0')
    await expect(page.getByRole('button', { name: '담당자 확인 저장' })).toBeDisabled()
    await page
      .getByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' })
      .selectOption('available')

    const [savedResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideSave.method,
      ),
      page.getByRole('button', { name: '안내문 저장' }).click(),
    ])
    expect(savedResponse.status()).toBe(ROUTES.actionGuideSave.ok)
    expect((await savedResponse.json()) as { status: string }).toMatchObject({ status: 'draft' })
    await expect(page.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' })).toHaveValue(
      SOURCE_TEXT,
    )
    await expect(page.getByRole('button', { name: 'TXT로 내려받기' })).toBeDisabled()
    await page
      .getByRole('checkbox', { name: '원문과 비교하여 이 안내문의 내용을 확인했습니다.' })
      .check()

    const [reviewedResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideSave.method,
      ),
      page.getByRole('button', { name: '담당자 확인 저장' }).click(),
    ])
    expect(reviewedResponse.status()).toBe(ROUTES.actionGuideSave.ok)
    const reviewed = (await reviewedResponse.json()) as ActionGuideResource
    expect(reviewed.status).toBe('reviewed')
    if (reviewed.guide === null) throw new Error('확인 저장 응답에 안내문이 없다.')
    const savedGuide = reviewed.guide
    const exportPath = `${guidePath}/export?guide_revision=${savedGuide.guide_revision}`

    const [exportResponse, download] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(exportPath) &&
          response.request().method() === ROUTES.actionGuideExport.method,
      ),
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'TXT로 내려받기' }).click(),
    ])
    expect(exportResponse.status()).toBe(ROUTES.actionGuideExport.ok)
    expect(download.suggestedFilename()).toBe('action-guide.txt')
    const path = await download.path()
    expect(path).not.toBeNull()
    const txt = await readFile(path as string, 'utf8')
    expect(txt).toContain(SOURCE_TEXT)
    expect(txt).toContain(caution)
    expect(txt).toContain('원문에 안내가 없습니다')

    // 다른 화면이 먼저 저장한 상태를 실제 API에 만든다. 첫 화면에서 편집한 내용은
    // CAS 409 뒤에도 사라지지 않아야 한다. 외부 저장도 같은 합성 계정/문서 안에서만 한다.
    const browserDraft = `${SOURCE_TEXT} 내용을 다시 확인해 주세요.`
    await page.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }).fill(browserDraft)
    await expect(page.getByRole('button', { name: 'TXT로 내려받기' })).toBeDisabled()
    const token = await storedToken(page)
    expect(token).not.toBeNull()
    const changedContent = {
      ...savedGuide.content,
      sections: savedGuide.content.sections.map((section) =>
        section.kind === 'eligibility'
          ? {
              ...section,
              items: section.items.map((item) => ({
                ...item,
                text: `${SOURCE_TEXT} 담당자와 확인해 주세요.`,
              })),
            }
          : section,
      ),
    }
    const concurrentSave = await page.request.put(api(guidePath), {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        candidate_id: null,
        expected_content_revision: savedGuide.based_on_content_revision,
        expected_guide_revision: savedGuide.guide_revision,
        content: changedContent,
        mark_reviewed: false,
      },
    })
    expect(concurrentSave.status()).toBe(ROUTES.actionGuideSave.ok)

    const [conflictResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideSave.method &&
          response.status() === ROUTES.actionGuideSave.conflict,
      ),
      page.getByRole('button', { name: '안내문 저장' }).click(),
    ])
    expect(conflictResponse.status()).toBe(ROUTES.actionGuideSave.conflict)
    await expect(page.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' })).toHaveValue(
      browserDraft,
    )
    await expect(page.getByText(/다른 화면에서 본문이나 안내문이 바뀌었습니다/)).toBeVisible()
    const [refreshedResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideRead.method,
      ),
      page.getByRole('button', { name: '상태 새로고침' }).click(),
    ])
    expect(refreshedResponse.status()).toBe(ROUTES.actionGuideRead.ok)
    await expect(page.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' })).toHaveValue(
      browserDraft,
    )
    await expect(page.getByRole('button', { name: '안내문 저장' })).toBeDisabled()
    await expect(page.getByRole('button', { name: '최신 안내문으로 다시 시작' })).toBeVisible()
  })

  test('본문 수정 중 생성하면 먼저 저장하고 이전 안내문의 출력은 막는다', async ({ page }) => {
    test.setTimeout(180_000)

    const log = new NetworkLog(page, API_BASE_URL)
    const conversionId = await openConvertedDocument(page)
    const conversionPath = `/conversions/${conversionId}`
    const guidePath = `${conversionPath}/action-guide`
    const jobsPath = `${guidePath}-jobs`

    await expect(page.getByRole('tab', { name: '행동 안내' })).toBeVisible()
    await page.getByRole('tab', { name: '행동 안내' }).click()
    await page.getByRole('button', { name: '안내문 만들기', exact: true }).click()
    const [firstJob] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(jobsPath) &&
          response.request().method() === ROUTES.actionGuideJobCreate.method,
      ),
      page.getByRole('button', { name: /크레딧으로 안내문 만들기/ }).click(),
    ])
    expect(firstJob.status(), await firstJob.text()).toBe(ROUTES.actionGuideJobCreate.accepted)
    await expect(page.getByRole('button', { name: '초안 사용' })).toBeVisible({ timeout: 90_000 })
    const [applied] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideSave.method,
      ),
      page.getByRole('button', { name: '초안 사용' }).click(),
    ])
    expect(applied.status()).toBe(ROUTES.actionGuideSave.ok)
    await page
      .getByRole('checkbox', { name: '원문과 비교하여 이 안내문의 내용을 확인했습니다.' })
      .check()
    const [reviewed] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(guidePath) &&
          response.request().method() === ROUTES.actionGuideSave.method,
      ),
      page.getByRole('button', { name: '담당자 확인 저장' }).click(),
    ])
    expect(reviewed.status()).toBe(ROUTES.actionGuideSave.ok)
    await expect(page.getByRole('button', { name: 'TXT로 내려받기' })).toBeEnabled()

    await page.getByRole('tab', { name: '본문 검수' }).click()
    await page
      .getByLabel(/^(쉬운 글 단위 1, .+|쉬운 글 결과 \(고칠 수 있습니다\))$/)
      .fill('본문을 수정했습니다. 가까운 지사에 방문하세요.')
    await page.getByRole('tab', { name: '행동 안내' }).click()
    await expect(page.getByRole('button', { name: 'TXT로 내려받기' })).toBeDisabled()

    await page.getByRole('button', { name: '안내문 다시 만들기' }).click()
    await expect(page.getByRole('button', { name: '본문 저장 후 만들기' })).toBeVisible()
    expect(
      log
        .apiRequests()
        .filter((request) => request.method === 'POST' && request.url === api(jobsPath)),
    ).toHaveLength(1)

    const [bodySaved, secondJob] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(conversionPath) &&
          response.request().method() === ROUTES.conversionReview.method,
      ),
      page.waitForResponse(
        (response) =>
          response.url() === api(jobsPath) &&
          response.request().method() === ROUTES.actionGuideJobCreate.method,
      ),
      page.getByRole('button', { name: '본문 저장 후 만들기' }).click(),
    ])
    expect(bodySaved.status()).toBe(ROUTES.conversionReview.ok)
    expect(secondJob.status()).toBe(ROUTES.actionGuideJobCreate.accepted)
    await expect(page.getByText(/이전 본문으로 만든 안내문입니다/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'TXT로 내려받기' })).toBeDisabled()
  })
})
