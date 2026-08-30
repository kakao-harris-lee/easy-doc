/**
 * E13 — 문서 등록 → worker 변환 → 검수 저장 → 피드백 제출 → 내보내기 수직 흐름.
 *
 * Kotlin `ConversionWorkerFlowTest` 가 DB·워커 상태 전이를 고정하고, React 단위
 * 테스트가 폴링·에디터·다운로드 UI를 고정한다. 여기서는 **실 브라우저·실 API·실
 * PostgreSQL·실 worker** 를 한 줄로 잇는다 — Sprint K1 완료 정의의 마지막 항목이다.
 *
 * worker 가 fake LLM(`LocalLlmProvider`) 으로 돌아가야 한다. Compose CI 는
 * `EASYDOC_LLM_PROVIDER=fake` 로 스택을 띄우고, `run-local.sh` 는 worker 를 같은
 * 설정으로 기동한다.
 */

import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'

import { ROUTES } from './contract'
import { API_BASE_URL, api, newAccount, signUpAndLand } from './support/app'
import { NetworkLog, signature } from './support/network'

/** fake LLM 이 돌려주는 고정 문장 — `LocalLlmProvider.CLEAN_REPLY`. */
const FAKE_EASY_TEXT = '오늘 서류를 내세요.'

/** 붙여넣기 원문. 개인정보 없이 짧게 — 마스킹 표는 비어 있어야 한다. */
const SOURCE_TEXT = '국민건강보험료를 납부하려면 가까운 지사를 방문하세요.'

/** 검수 저장 후 내려받기에 실릴 수정본. */
const REVIEWED_TEXT = 'E2E 검수본입니다. 가까운 지사에 방문하세요.'

/** 파일럿 게이트 ① 피드백 값. 자유 의견에는 문서 본문을 옮기지 않는다(폼 안내와 같은 규칙). */
const FEEDBACK_MINUTES = '25'
const FEEDBACK_COMMENT = 'E2E 확인용 의견입니다.'

type ConversionStatus = 'pending' | 'processing' | 'done' | 'failed'

interface DocumentCreatedBody {
  document_id: string
  conversion_id: string
  status: ConversionStatus
}

interface ConversionBody {
  id: string
  status: ConversionStatus
  easy_text: string | null
  edited_text: string | null
}

/** GET /conversions/{id} 응답에서 status 를 모은다 — worker 상태 전이 관측용. */
function trackConversionStatuses(page: import('@playwright/test').Page) {
  const statuses: ConversionStatus[] = []
  page.on('response', (response) => {
    const url = response.url()
    if (response.request().method() !== ROUTES.conversionRead.method) {
      return
    }
    const path = url.startsWith(API_BASE_URL) ? url.slice(API_BASE_URL.length) : url
    if (!/^\/conversions\/[^/?]+$/.test(path)) {
      return
    }
    void response
      .json()
      .then((body) => {
        const status = (body as ConversionBody).status
        if (statuses.at(-1) !== status) {
          statuses.push(status)
        }
      })
      .catch(() => {
        // 페이지가 닫히는 중이면 본문을 못 읽을 수 있다 — 관측 실패를 통과로 바꾸지 않는다.
      })
  })
  return statuses
}

test.describe('변환 수직 흐름', () => {
  test('E13 붙여넣기 등록 → worker 완료 → 검수 저장 → 피드백 제출 → txt 내려받기', async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const log = new NetworkLog(page, API_BASE_URL)
    const observedStatuses = trackConversionStatuses(page)
    await signUpAndLand(page, newAccount())

    await page.getByLabel('문서 제목').fill('E2E 건강보험료 안내')
    await page.getByLabel('바꿀 글').fill(SOURCE_TEXT)

    const [createdResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(ROUTES.documentCreate.path) &&
          response.request().method() === ROUTES.documentCreate.method,
      ),
      // 대표 버튼은 AI가 아니라 결과와 작업을 말한다(DESIGN.md §6.2) — `쉬운 글로 바꾸기`에서
      // 바뀐 문구다. 이 화면의 제출 버튼은 하나뿐이라 `exact`로 좁혀 둔다.
      page.getByRole('button', { name: '쉬운 글 초안 만들기', exact: true }).click(),
    ])
    expect(createdResponse.status()).toBe(ROUTES.documentCreate.accepted)

    const created = (await createdResponse.json()) as DocumentCreatedBody
    expect(created.status).toBe('pending')
    expect(created.conversion_id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    )

    const conversionPath = `/conversions/${created.conversion_id}`
    expect(new URL(page.url()).pathname).toBe(conversionPath)

    // worker 가 fake LLM 으로 끝낼 때까지 기다린다 — 폴링 UI 가 검수 화면으로 바뀐다.
    await expect(page.getByRole('heading', { name: '쉬운 글 검수' })).toBeVisible({
      timeout: 90_000,
    })

    const editor = page.getByLabel('쉬운 글 결과 (고칠 수 있습니다)')
    await expect(editor).toHaveValue(FAKE_EASY_TEXT)
    await expect(page.getByLabel('원본 (읽기 전용)')).toHaveValue(SOURCE_TEXT)
    await expect(page.getByRole('note')).toHaveText(/AI가 만든 초안입니다/)

    expect(observedStatuses.at(-1)).toBe('done')
    expect(observedStatuses).toContain('pending')
    expect(observedStatuses.every((status) => status !== 'failed')).toBe(true)

    await editor.fill(REVIEWED_TEXT)

    const [savedResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(conversionPath) &&
          response.request().method() === ROUTES.conversionReview.method,
      ),
      page.getByRole('button', { name: '검수 내용 저장', exact: true }).click(),
    ])
    expect(savedResponse.status()).toBe(ROUTES.conversionReview.ok)
    await expect(page.getByText('검수 내용을 저장했습니다.')).toBeVisible()

    // 검수를 마친 자리에서 파일럿 판정용 피드백을 남긴다 — 백엔드는 done 이 아닌 변환에
    // 대해 409 로 막으므로, 이 단계는 검수 저장 뒤에 와야 한다.
    await page.getByLabel('조금 고쳐서 쓰겠다').check()
    await page.getByLabel('4점').check()
    await page.getByLabel('이번 건 소요 시간(분)').fill(FEEDBACK_MINUTES)
    await page.getByLabel('의견 (선택)').fill(FEEDBACK_COMMENT)

    const feedbackPath = `${conversionPath}/feedback`
    const [feedbackResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(feedbackPath) &&
          response.request().method() === ROUTES.conversionFeedback.method,
      ),
      page.getByRole('button', { name: '의견 보내기', exact: true }).click(),
    ])
    expect(feedbackResponse.status()).toBe(ROUTES.conversionFeedback.ok)
    await expect(page.getByText('의견을 보냈습니다. 감사합니다.')).toBeVisible()

    const exportPath = `${conversionPath}/export?format=txt`
    const downloadPromise = page.waitForEvent('download')
    const [exportResponse, download] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url() === api(exportPath) &&
          response.request().method() === ROUTES.conversionExport.method,
      ),
      downloadPromise,
      // §6.5 «한 번의 명확한 행동» — 저장하지 않은 수정이 있으면 이 버튼이 저장까지
      // 한다. 여기서는 바로 위에서 이미 저장했으므로 한 걸음짜리 이름이다.
      page.getByRole('button', { name: 'TXT로 내려받기', exact: true }).click(),
    ])
    expect(exportResponse.status()).toBe(ROUTES.conversionExport.ok)

    // §6.5 — 내려받은 파일명은 원본 제목에 `-쉬운글` 이 붙어 원본 파일과 구분된다. 서버가
    // `Content-Disposition` 의 `filename*` 으로 주고, 화면은 그것을 그대로 anchor 의
    // 내려받기 이름으로 쓴다 — 그 두 걸음이 실제로 이어지는지는 여기서만 잴 수 있다.
    expect(download.suggestedFilename()).toBe('E2E 건강보험료 안내-쉬운글.txt')

    const savedPath = await download.path()
    expect(savedPath).not.toBeNull()
    const fileText = await readFile(savedPath as string, 'utf8')
    expect(fileText).toContain(REVIEWED_TEXT)

    await expect(page.getByText('TXT 파일을 내려받았습니다.')).toBeVisible()

    const calls = await log.apiCalls()
    const callSignatures = calls.map(signature)
    expect(callSignatures).toContain(
      `${ROUTES.documentCreate.method} ${ROUTES.documentCreate.path} ${ROUTES.documentCreate.accepted}`,
    )
    expect(
      callSignatures.filter(
        (entry) =>
          entry === `${ROUTES.conversionRead.method} ${conversionPath} ${ROUTES.conversionRead.ok}`,
      ).length,
    ).toBeGreaterThan(0)
    expect(callSignatures).toContain(
      `${ROUTES.conversionReview.method} ${conversionPath} ${ROUTES.conversionReview.ok}`,
    )
    expect(callSignatures).toContain(
      `${ROUTES.conversionFeedback.method} ${feedbackPath} ${ROUTES.conversionFeedback.ok}`,
    )
    expect(callSignatures).toContain(
      `${ROUTES.conversionExport.method} ${exportPath} ${ROUTES.conversionExport.ok}`,
    )
  })
})
