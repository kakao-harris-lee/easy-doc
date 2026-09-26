/**
 * R7 ER-17 — 실제 브라우저·API·DB·worker를 거치는 문맥 기반 그림 제안 흐름.
 * `compose.e2e.yml`이 `EASYDOC_ILLUSTRATION_SUGGESTIONS_ENABLED`와 worker의
 * `illustration-suggestion-fake` 프로필을 이 스택에서만 켠다. 이 스펙은 유료 LLM을
 * 호출하지 않으며, 그 스택의 단가는 0이라 「추가 차감 없음」으로 표시된다.
 */

import { expect, test, type Page } from '@playwright/test'

import type { IllustrationSuggestionsResource } from '../src/api/types'
import { ROUTES } from './contract'
import { API_BASE_URL, api, newAccount, signUpAndLand, verifyEmail } from './support/app'
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

/** 각 시나리오가 다른 계정·문서를 쓴다 — 작업·이용량 상태를 테스트 사이에 공유하지 않는다. */
async function openConvertedDocument(page: Page): Promise<string> {
  const account = newAccount()
  await signUpAndLand(page, account)
  await verifyEmail(page, account)
  await page.getByLabel('문서 제목').fill(`E2E 그림 제안 ${crypto.randomUUID().slice(0, 8)}`)
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
  await expect(page.getByRole('heading', { name: '쉬운 글 확인' })).toBeVisible({
    timeout: 90_000,
  })
  return conversionId
}

test.describe('ER-17 문맥 기반 그림 제안', () => {
  test('요청 전 안내 → 접수 → 결과 확인 → 재방문 복구 → 본문 수정 후 이전 버전', async ({
    page,
  }) => {
    test.setTimeout(180_000)

    const log = new NetworkLog(page, API_BASE_URL)
    const conversionId = await openConvertedDocument(page)
    const conversionPath = `/conversions/${conversionId}`
    const suggestionsPath = `${conversionPath}/illustration-suggestions`
    const jobsPath = `${conversionPath}/illustration-suggestion-jobs`

    // 1. 요청 전 — 무엇을 하는지·차감량·파일에 그림이 들어가지 않는다는 점을 먼저 읽힌다.
    await page.getByRole('button', { name: '그림으로 설명하기 (선택)', exact: true }).click()
    const panel = page.getByRole('region', { name: '그림 제안' })
    await expect(panel.getByRole('heading', { name: '그림 제안' })).toBeVisible()
    await expect(panel.getByText(/이 단계에서는 그림을 만들지 않습니다/)).toBeVisible()
    await expect(
      panel.getByText(/DOCX·HWPX·TXT 파일에는 새 그림이 포함되지 않습니다/),
    ).toBeVisible()
    // compose.e2e.yml의 단가는 0이다 — 미설정(null)과 다르다는 것을 화면 문구로 확인한다.
    await expect(panel.getByText('추가 차감 없음')).toBeVisible()

    const requestButton = panel.getByRole('button', { name: '그림 제안 확인', exact: true })
    await expect(requestButton).toBeEnabled()
    // 화면을 열었다는 것만으로 분석을 요청하지 않는다(명세 §1).
    expect(
      log
        .apiRequests()
        .filter((request) => request.method === 'POST' && request.url === api(jobsPath)),
    ).toHaveLength(0)

    // 2. 접수 — 멱등 키가 붙고 202로 돌아온다.
    const [createdResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(jobsPath) &&
          response.request().method() === ROUTES.illustrationSuggestionJobCreate.method,
      ),
      requestButton.click(),
    ])
    expect(createdResponse.status(), await createdResponse.text()).toBe(
      ROUTES.illustrationSuggestionJobCreate.accepted,
    )
    const created = (await createdResponse.json()) as CreatedJob
    expect(created.job_id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(created.request_id).toMatch(/^[0-9a-f-]{36}$/i)
    // 단가 0은 이용량 거래 행을 만들지 않는다(명세 §3).
    expect(created.reserved_credits).toBe(0)
    // fake 작업은 첫 폴링 전에 완료될 수 있으므로 중간 상태의 노출 시간을 가정하지 않는다.

    // 3. 결과 — fake runner는 원문 첫 줄을 근거로 절차 제안 1건을 낸다.
    await expect(panel.getByRole('heading', { name: '제안 1 · 절차' })).toBeVisible({
      timeout: 90_000,
    })
    await expect(panel.getByText(/원문 1줄:/)).toBeVisible()
    // 무료 테스트는 외부 호출 없이 생성·확인·적용·제거를 체험한다.
    await expect(panel.getByText(/무료 테스트 모드:/)).toBeVisible()
    await panel.getByRole('button', { name: '이 내용으로 그림 만들기' }).click()
    await expect(panel.getByRole('img')).toBeVisible()
    const apply = panel.getByRole('button', { name: '문서 미리보기에 추가' })
    await expect(apply).toBeDisabled()
    await panel.getByLabel('그림 대체텍스트').fill('확인한 테스트 그림 설명')
    await panel.getByRole('checkbox', { name: /원문 근거와 그림 구성/ }).check()
    await apply.click()
    await expect(panel.getByRole('heading', { name: '문서 적용 미리보기' })).toBeVisible()
    await panel.getByRole('button', { name: '문서 미리보기에서 제거' }).click()
    await expect(panel.getByRole('heading', { name: '문서 적용 미리보기' })).toHaveCount(0)

    // 4. 재방문 — 브라우저 저장소가 아니라 서버 조회로 같은 결과를 되살린다.
    const callsBeforeReload = (await log.apiCalls()).length
    await page.reload()
    await expect(page.getByRole('heading', { name: '쉬운 글 확인' })).toBeVisible({
      timeout: 90_000,
    })
    await page.getByRole('button', { name: '그림으로 설명하기 (선택)', exact: true }).click()
    await expect(panel.getByRole('heading', { name: '제안 1 · 절차' })).toBeVisible({
      timeout: 90_000,
    })
    const recoveredCalls = (await log.apiCalls()).slice(callsBeforeReload).map(signature)
    expect(recoveredCalls).toContain(
      `${ROUTES.illustrationSuggestionsRead.method} ${suggestionsPath} ${ROUTES.illustrationSuggestionsRead.ok}`,
    )
    expect(recoveredCalls).toContain(
      `${ROUTES.illustrationSuggestionJobs.method} ${jobsPath} ${ROUTES.illustrationSuggestionJobs.ok}`,
    )
    // 재방문이 새 작업을 만들거나 이용량을 예약하지 않는다.
    expect(
      log
        .apiRequests()
        .filter((request) => request.method === 'POST' && request.url === api(jobsPath)),
    ).toHaveLength(1)

    // 5. 본문을 고치는 중에는 요청을 막는다(저장 먼저).
    await page
      .getByLabel(/^(쉬운 글 단위 1, .+|쉬운 글 결과 \(고칠 수 있습니다\))$/)
      .fill('본문을 수정했습니다. 가까운 지사에 방문하세요.')
    await expect(panel.getByText(/저장하지 않은 본문 수정이 있습니다/)).toBeVisible()
    await expect(panel.getByRole('button', { name: /그림 제안( 다시)? 확인/ })).toBeDisabled()

    // 6. 이전 버전 — 저장으로 revision이 오르면 패널이 스스로 다시 읽고, 서버는 읽는
    //    시점에 stale로 판정한다. 결과는 남기되 재분석을 권한다.
    const [saved, staleResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(conversionPath) &&
          response.request().method() === ROUTES.conversionReview.method,
      ),
      page.waitForResponse(
        (response) =>
          response.url() === api(suggestionsPath) &&
          response.request().method() === ROUTES.illustrationSuggestionsRead.method,
      ),
      page.getByRole('button', { name: '검수 내용 저장', exact: true }).click(),
    ])
    expect(saved.status()).toBe(ROUTES.conversionReview.ok)
    expect(staleResponse.status()).toBe(ROUTES.illustrationSuggestionsRead.ok)
    const staleResource = (await staleResponse.json()) as IllustrationSuggestionsResource
    expect(staleResource.status).toBe('stale')
    await expect(panel.getByText(/이전 버전의 본문으로 만든 제안입니다/)).toBeVisible()
    await expect(panel.getByRole('button', { name: '이 내용으로 그림 만들기' })).toHaveCount(0)
    await expect(panel.getByRole('button', { name: '그림 제안 다시 확인' })).toBeEnabled()
  })
})
