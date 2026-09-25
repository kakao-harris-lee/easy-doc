import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, login } from './auth'
import {
  ApiError,
  NETWORK_ERROR_STATUS,
  analyzeReviewSupport,
  createActionGuideJob,
  createDocumentFromFile,
  createDocumentFromText,
  createIllustrationSuggestionJob,
  downloadExport,
  downloadActionGuide,
  downloadReviewHistory,
  getActionGuide,
  getActionGuideJob,
  getExplanations,
  getIllustrationPlacements,
  getIllustrationSuggestionJob,
  getIllustrationSuggestions,
  getIllustrations,
  getReviewHistory,
  getReviewSupport,
  listActionGuideJobs,
  listDocuments,
  listIllustrationSuggestionJobs,
  putIllustrationPlacements,
  reconvertUnit,
  saveReview,
  saveActionGuide,
  setUnauthorizedHandler,
  updateReviewSupportItem,
  updateReviewSupportItems,
  illustrationImageUrl,
} from './client'
import { readToken, writeToken } from './token'
import type { ActionGuideContent } from './types'
import { userResponse } from '../test/factories'

/** JSON 응답을 흉내 낸다. */
function jsonResponse(status: number, payload: unknown): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn<typeof fetch>()
const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8000').replace(
  /\/+$/,
  '',
)

beforeEach(() => {
  window.localStorage.clear()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  setUnauthorizedHandler(null)
  vi.unstubAllGlobals()
})

describe('요청 조립', () => {
  it('저장된 토큰을 Authorization 헤더로 붙인다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(jsonResponse(200, userResponse()))

    await fetchMe()

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/auth/me`)
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token-abc')
  })

  it('인증 전 호출(로그인)에는 토큰을 붙이지 않는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      jsonResponse(200, { access_token: 't', token_type: 'bearer', expires_in: 3600 }),
    )

    await login({ email: 'a@example.com', password: 'password123' })

    const init = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(init?.headers).get('Authorization')).toBeNull()
    expect(init?.body).toBe(JSON.stringify({ email: 'a@example.com', password: 'password123' }))
  })

  it('목록 조회의 페이지 인자를 쿼리 문자열로 넘긴다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { items: [], limit: 20, offset: 0, has_more: false }),
    )

    await listDocuments({ limit: 20, offset: 40 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/documents?limit=20&offset=40`)
  })

  it('본문 저장에 기대 content revision을 함께 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: 'c1', content_revision: 4 }))

    await saveReview('c1', '고친 글', 3)

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1`)
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(init?.body as string)).toEqual({
      edited_text: '고친 글',
      expected_content_revision: 3,
    })
  })
})

describe('review support API', () => {
  it('저장 상태 조회와 현재 revision 분석 경로를 구분한다', async () => {
    const payload = { status: 'not_generated', assessment: null }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))

    await getReviewSupport('c1')
    await analyzeReviewSupport('c1', { expected_content_revision: 7 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/review-support`)
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('GET')
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/review-support`)
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe('POST')
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual({
      expected_content_revision: 7,
    })
  })

  it('항목 저장에 assessment·본문·검수 revision과 사유를 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { status: 'ready', assessment: null }))

    await updateReviewSupportItem('c1', 'item-1', {
      assessment_id: 'assessment-1',
      expected_content_revision: 2,
      expected_review_revision: 5,
      state: 'not_applicable',
      reason: '이 문서에는 신청 절차가 없습니다.',
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1/review-support/items/item-1`)
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(init?.body as string)).toEqual({
      assessment_id: 'assessment-1',
      expected_content_revision: 2,
      expected_review_revision: 5,
      state: 'not_applicable',
      reason: '이 문서에는 신청 절차가 없습니다.',
    })
  })

  it('문단 항목 여러 개를 batch/CAS 경로에 한 요청으로 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { status: 'ready', assessment: null }))

    await updateReviewSupportItems('c1', {
      assessment_id: 'assessment-1',
      expected_content_revision: 2,
      expected_review_revision: 5,
      item_ids: ['item-1', 'item-2'],
      state: 'confirmed',
      reason: null,
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1/review-support/items`)
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(init?.body as string)).toMatchObject({
      item_ids: ['item-1', 'item-2'],
      expected_content_revision: 2,
      expected_review_revision: 5,
    })
  })
})

describe('document reading level API', () => {
  const created = {
    document_id: 'd1',
    conversion_id: 'c1',
    status: 'pending',
    char_count: 10,
    reading_level: 'grade_3_4',
    reserved_credits: 0.2,
  }

  it('JSON 문서 요청에 선택 수준을 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202, created))

    await createDocumentFromText('본문', null, '제목', false, 'grade_3_4')

    const body = JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)
    expect(body.reading_level).toBe('grade_3_4')
  })

  it('multipart 문서 요청에도 같은 선택 수준 문자열을 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202, created))

    await createDocumentFromFile(
      new File(['본문'], '안내.txt', { type: 'text/plain' }),
      null,
      '제목',
      false,
      'grade_3_4',
    )

    const form = fetchMock.mock.calls[0]?.[1]?.body as FormData
    expect(form.get('reading_level')).toBe('grade_3_4')
  })
})

describe('review history API', () => {
  it('최근 기록과 opaque cursor를 쿼리로 요청한다', async () => {
    const payload = {
      conversion_id: 'c1',
      current_content_revision: 3,
      events: [],
      next_cursor: 'next-1',
    }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))
    const controller = new AbortController()

    await getReviewHistory('c1', { limit: 20 }, controller.signal)
    await getReviewHistory('c1', { cursor: 'next/한글', limit: 20 }, controller.signal)

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/review-history?limit=20`,
    )
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBe(controller.signal)
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/review-history?cursor=next%2F%ED%95%9C%EA%B8%80&limit=20`,
    )
  })

  it('Content-Disposition 파일명을 보존하고 export 오류를 ApiError로 올린다', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('event text', {
        status: 200,
        headers: { 'Content-Disposition': "attachment; filename*=UTF-8''history.txt" },
      }),
    )
    const downloaded = await downloadReviewHistory('c1')
    expect(downloaded.filename).toBe('history.txt')
    expect(await downloaded.blob.text()).toBe('event text')

    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, { detail: '기록이 아직 준비되지 않았습니다.' }),
    )
    await expect(downloadReviewHistory('c1')).rejects.toMatchObject({
      status: 409,
      message: '기록이 아직 준비되지 않았습니다.',
    })
  })
})

describe('explanations API', () => {
  it('본문 기준 용어 설명을 쿼리 없이 요청하고 signal을 전달한다', async () => {
    const payload = {
      conversion_id: 'c1',
      current_content_revision: 3,
      explanations: [],
    }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))
    const controller = new AbortController()

    await getExplanations('c1', controller.signal)

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/explanations`)
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBe(controller.signal)
  })
})

describe('illustrations API', () => {
  it('검수된 그림 카탈로그를 쿼리 없이 요청하고 signal을 전달한다', async () => {
    const payload = { illustrations: [] }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))
    const controller = new AbortController()

    await getIllustrations(controller.signal)

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/illustrations`)
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBe(controller.signal)
  })

  it('그림 이미지의 상대 경로를 API base와 합쳐 절대 URL로 만든다', () => {
    expect(illustrationImageUrl('/illustrations/visit-office/image')).toBe(
      `${apiBaseUrl}/illustrations/visit-office/image`,
    )
  })
})

describe('illustration placements API (ER-16)', () => {
  it('저장된 그림 배치를 쿼리 없이 요청하고 signal을 전달한다', async () => {
    const payload = {
      conversion_id: 'c1',
      current_content_revision: 4,
      placements_content_revision: 3,
      stale: true,
      placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
    }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, payload))
    const controller = new AbortController()

    await getIllustrationPlacements('c1', controller.signal)

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/illustration-placements`,
    )
    expect(fetchMock.mock.calls[0]?.[1]?.signal).toBe(controller.signal)
  })

  it('그림 배치 저장에 기대 content revision과 배치 목록을 함께 보낸다', async () => {
    const payload = {
      conversion_id: 'c1',
      current_content_revision: 4,
      placements_content_revision: 4,
      stale: false,
      placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
    }
    fetchMock.mockResolvedValue(jsonResponse(200, payload))

    await putIllustrationPlacements('c1', {
      expected_content_revision: 4,
      placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1/illustration-placements`)
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(init?.body as string)).toEqual({
      expected_content_revision: 4,
      placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
    })
  })
})

describe('action guide job API', () => {
  const content: ActionGuideContent = {
    schema_version: 1,
    sections: [
      {
        kind: 'eligibility',
        status: 'available',
        items: [
          {
            text: '19세 이상 신청 가능',
            cautions: [],
            source_anchors: [{ source_unit_indexes: [0], quote: '19세 이상 신청 가능' }],
          },
        ],
      },
      { kind: 'benefits', status: 'not_in_source', items: [] },
      { kind: 'documents', status: 'not_in_source', items: [] },
      { kind: 'steps', status: 'not_in_source', items: [] },
      { kind: 'exceptions', status: 'not_in_source', items: [] },
      { kind: 'contact', status: 'not_in_source', items: [] },
    ],
  }
  const job = {
    job_id: 'job-1',
    request_id: 'request-1',
    status: 'queued',
    based_on_content_revision: 7,
    reserved_credits: 2,
    failure_code: null,
    created_at: '2026-09-18T00:00:00Z',
    updated_at: '2026-09-18T00:00:00Z',
    candidate_id: null,
    candidate_state: null,
    content: null,
  }

  it('생성 완료 작업은 후보 ID, 현재성, 고정 6섹션을 읽는다', async () => {
    const completed = {
      ...job,
      status: 'succeeded',
      candidate_id: 'candidate-1',
      candidate_state: 'current',
      content,
    }
    fetchMock.mockResolvedValue(jsonResponse(200, completed))

    const actual = await getActionGuideJob('c1', 'job-1')

    expect(actual.candidate_id).toBe('candidate-1')
    expect(actual.candidate_state).toBe('current')
    expect(actual.content?.sections).toHaveLength(6)
  })

  it('저장 안내문을 읽고 두 revision과 확인 의사를 함께 저장한다', async () => {
    const resource = {
      status: 'not_generated',
      guide: null,
      active_job_id: 'job-1',
      latest_job_id: 'job-1',
    }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, resource))
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        status: 'draft',
        guide: {
          guide_id: 'guide-1',
          based_on_content_revision: 7,
          guide_revision: 1,
          status: 'draft',
          content,
          reviewed_at: null,
          reviewed_by: null,
        },
        active_job_id: null,
        latest_job_id: null,
      }),
    )

    expect((await getActionGuide('c1')).active_job_id).toBe('job-1')
    const saved = await saveActionGuide('c1', {
      candidate_id: 'candidate-1',
      expected_content_revision: 7,
      expected_guide_revision: null,
      content,
      mark_reviewed: false,
    })

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/action-guide`)
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/action-guide`)
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe('PUT')
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toMatchObject({
      candidate_id: 'candidate-1',
      expected_content_revision: 7,
      expected_guide_revision: null,
      mark_reviewed: false,
    })
    expect(saved.guide?.guide_revision).toBe(1)
  })

  it('확인한 안내문 revision을 쿼리로 보내 TXT로 받는다', async () => {
    fetchMock.mockResolvedValue(new Response('신청 대상\n19세 이상', { status: 200 }))

    const file = await downloadActionGuide('c1', 3)

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/action-guide/export?guide_revision=3`,
    )
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('GET')
    expect(file.filename).toBe('action-guide.txt')
    expect(await file.blob.text()).toBe('신청 대상\n19세 이상')
  })

  it('목록과 개별 작업 조회 경로를 구분한다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        active_job: job,
        latest_job: job,
        required_credits: 2,
        available_credits: 8,
      }),
    )
    fetchMock.mockResolvedValueOnce(jsonResponse(200, job))

    await listActionGuideJobs('c1')
    await getActionGuideJob('c1', 'job-1')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/action-guide-jobs`)
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('GET')
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/action-guide-jobs/job-1`,
    )
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe('GET')
  })

  it('생성 요청은 멱등 키와 두 revision을 보내고 202 크레딧 헤더를 읽는다', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(job), {
        status: 202,
        headers: {
          'Content-Type': 'application/json',
          Location: '/conversions/c1/action-guide-jobs/job-1',
          'X-Credit-Balance': '8.1',
        },
      }),
    )

    const result = await createActionGuideJob('c1', {
      request_id: 'request-1',
      expected_content_revision: 7,
      expected_guide_revision: null,
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1/action-guide-jobs`)
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({
      request_id: 'request-1',
      expected_content_revision: 7,
      expected_guide_revision: null,
    })
    expect(result.job).toEqual(job)
    expect(result.creditBalance).toBe(8.1)
  })
})

describe('illustration suggestion API (R7 ER-17)', () => {
  const job = {
    job_id: 'job-7',
    request_id: 'request-7',
    status: 'queued',
    based_on_content_revision: 7,
    reserved_credits: 1.5,
    failure_code: null,
    created_at: '2026-09-24T00:00:00Z',
    updated_at: '2026-09-24T00:00:00Z',
  }

  it('접수는 멱등 키와 본문 revision만 보내고 202 이용량 헤더를 읽는다', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(job), {
        status: 202,
        headers: {
          'Content-Type': 'application/json',
          Location: '/conversions/c1/illustration-suggestion-jobs/job-7',
          'X-Credit-Balance': '6.4',
        },
      }),
    )

    const result = await createIllustrationSuggestionJob('c1', {
      request_id: 'request-7',
      expected_content_revision: 7,
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1/illustration-suggestion-jobs`)
    expect(init?.method).toBe('POST')
    // 행동 안내와 달리 두 번째 revision 축이 없다 — 계약에 없는 필드를 보내면 422다.
    expect(JSON.parse(init?.body as string)).toEqual({
      request_id: 'request-7',
      expected_content_revision: 7,
    })
    expect(result.job).toEqual(job)
    expect(result.creditBalance).toBe(6.4)
  })

  it('이용량 헤더가 없거나 숫자가 아니면 잔액은 null이다', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(job), {
        status: 202,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const result = await createIllustrationSuggestionJob('c1', {
      request_id: 'request-7',
      expected_content_revision: 7,
    })

    expect(result.creditBalance).toBeNull()
  })

  it('작업 목록과 단건 조회 경로를 구분한다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        active_job: job,
        latest_job: job,
        required_credits: 1.5,
        available_credits: 6.4,
      }),
    )
    fetchMock.mockResolvedValueOnce(jsonResponse(200, job))

    const collection = await listIllustrationSuggestionJobs('c1')
    await getIllustrationSuggestionJob('c1', 'job-7')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/illustration-suggestion-jobs`,
    )
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('GET')
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/illustration-suggestion-jobs/job-7`,
    )
    expect(collection.required_credits).toBe(1.5)
  })

  it('제안 조회는 상태·근거·버린 수를 그대로 읽는다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        status: 'ready',
        content_revision: 7,
        based_on_content_revision: 7,
        required_credits: 1.5,
        dropped_count: 2,
        suggestions: [
          {
            suggestion_id: 'suggestion-1',
            purpose: 'procedure',
            reason: '신청 순서를 그림으로 보면 이해하기 쉽다',
            body_range: { start: 0, end: 2 },
            source_anchors: [{ source_unit_indexes: [0], quote: '신청서를 제출합니다' }],
            scenes: ['신청서를 내는 장면'],
            preserved_facts: [],
            alt_text_draft: '신청 순서를 보여 주는 그림',
          },
        ],
      }),
    )

    const resource = await getIllustrationSuggestions('c1')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `${apiBaseUrl}/conversions/c1/illustration-suggestions`,
    )
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('GET')
    expect(resource.status).toBe('ready')
    expect(resource.dropped_count).toBe(2)
    expect(resource.suggestions[0]?.body_range).toEqual({ start: 0, end: 2 })
    expect(resource.suggestions[0]?.source_anchors[0]?.source_unit_indexes).toEqual([0])
  })

  it('분석 전에는 근거 revision과 제안이 비어 있다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        status: 'not_analyzed',
        content_revision: 1,
        based_on_content_revision: null,
        required_credits: null,
        dropped_count: 0,
        suggestions: [],
      }),
    )

    const resource = await getIllustrationSuggestions('c1')

    expect(resource.status).toBe('not_analyzed')
    expect(resource.based_on_content_revision).toBeNull()
    // 단가 미설정은 0(무과금)이 아니라 null이다 — 화면이 두 경우를 구분한다.
    expect(resource.required_credits).toBeNull()
    expect(resource.suggestions).toEqual([])
  })
})

describe('401 처리', () => {
  it('토큰을 들고 간 요청이 401이면 토큰을 버리고 앱에 알린다', async () => {
    writeToken('expired-token')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    fetchMock.mockResolvedValue(jsonResponse(401, { detail: '유효하지 않은 인증 정보입니다' }))

    await expect(fetchMe()).rejects.toThrow(ApiError)

    expect(readToken()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('로그인 실패(401)로는 저장된 토큰을 건드리지 않는다', async () => {
    writeToken('valid-token')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    fetchMock.mockResolvedValue(
      jsonResponse(401, { detail: '이메일 또는 비밀번호가 올바르지 않습니다' }),
    )

    await expect(login({ email: 'a@example.com', password: 'wrongpassword' })).rejects.toThrow(
      ApiError,
    )

    expect(readToken()).toBe('valid-token')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})

describe('오류 해석', () => {
  it('detail 문자열을 그대로 메시지로 쓴다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { detail: '이미 가입된 이메일입니다' }))

    await expect(login({ email: 'a@example.com', password: 'password123' })).rejects.toMatchObject({
      status: 409,
      message: '이미 가입된 이메일입니다',
    })
  })

  it('detail 배열(422 검증 오류)에서 msg만 모은다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(422, {
        detail: [
          { loc: ['body', 'email'], msg: '이메일을 입력해 주세요', type: 'missing' },
          { loc: ['body', 'password'], msg: '비밀번호를 입력해 주세요', type: 'missing' },
        ],
      }),
    )

    await expect(login({ email: '', password: '' })).rejects.toMatchObject({
      status: 422,
      message: '이메일을 입력해 주세요\n비밀번호를 입력해 주세요',
    })
  })

  it('JSON이 아닌 오류 응답에도 안내 문구를 만든다', async () => {
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }))

    await expect(fetchMe()).rejects.toMatchObject({
      status: 502,
      message: expect.stringContaining('요청을 처리하지 못했습니다'),
    })
  })

  it('연결 자체가 실패하면 네트워크 오류로 알린다', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(fetchMe()).rejects.toMatchObject({
      status: NETWORK_ERROR_STATUS,
      message: expect.stringContaining('서버에 연결하지 못했습니다'),
    })
  })
})

describe('내보내기', () => {
  it('format 쿼리를 붙이고 filename* 을 파일명으로 쓴다', async () => {
    writeToken('token-abc')
    const filename = '기초연금.txt'
    fetchMock.mockResolvedValue(
      new Response('본문', {
        status: 200,
        headers: {
          'Content-Disposition': `attachment; filename="easy-read.txt"; filename*=UTF-8''${encodeURIComponent(filename)}`,
        },
      }),
    )

    const file = await downloadExport('c1', 'txt')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/export?format=txt`)
    expect(file.filename).toBe(filename)
    expect(await file.blob.text()).toBe('본문')
  })

  it('filename* 이 없으면 파일명을 null 로 둔다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response('본문', {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="easy-read.docx"' },
      }),
    )

    const file = await downloadExport('c1', 'docx')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${apiBaseUrl}/conversions/c1/export?format=docx`)
    expect(file.filename).toBeNull()
  })
})

describe('reconvertUnit', () => {
  it('경로에 source_unit_index를 싣고 본문에는 매핑·지문만 보낸다', async () => {
    writeToken('token-abc')
    const fingerprint = 'a'.repeat(64)
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        candidate_text: '다시 쓴 문단입니다.',
        source_unit_index: 2,
        easy_unit_indexes: [3],
        easy_text_fingerprint: fingerprint,
        llm_calls_used: 1,
        remaining_call_budget: 19,
      }),
    )

    const response = await reconvertUnit('c1', 2, {
      easy_unit_indexes: [3],
      easy_text_fingerprint: fingerprint,
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/conversions/c1/units/2/reconvert`)
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({
      easy_unit_indexes: [3],
      easy_text_fingerprint: fingerprint,
    })
    expect(response.candidate_text).toBe('다시 쓴 문단입니다.')
    expect(response.remaining_call_budget).toBe(19)
  })

  it('429는 X-Remaining-Call-Budget을 ApiError.remainingCallBudget으로 읽는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '재변환 호출 예산을 모두 사용했습니다' }), {
        status: 429,
        headers: {
          'Content-Type': 'application/json',
          'X-Remaining-Call-Budget': '0',
        },
      }),
    )

    await expect(
      reconvertUnit('c1', 0, { easy_unit_indexes: [], easy_text_fingerprint: 'a'.repeat(64) }),
    ).rejects.toMatchObject({ status: 429, remainingCallBudget: 0 })
  })

  it('503은 Retry-After를 ApiError.retryAfterSeconds로 읽는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '동시 재변환 한도에 도달했습니다' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json', 'Retry-After': '1' },
      }),
    )

    await expect(
      reconvertUnit('c1', 0, { easy_unit_indexes: [], easy_text_fingerprint: 'a'.repeat(64) }),
    ).rejects.toMatchObject({ status: 503, retryAfterSeconds: 1 })
  })

  it('헤더가 없는 오류(502)는 remainingCallBudget·retryAfterSeconds가 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(jsonResponse(502, { detail: '구글에 연결하지 못했습니다' }))

    await expect(
      reconvertUnit('c1', 0, { easy_unit_indexes: [], easy_text_fingerprint: 'a'.repeat(64) }),
    ).rejects.toMatchObject({ status: 502, retryAfterSeconds: null, remainingCallBudget: null })
  })

  it('정수가 아닌 헤더 값(LOW 리뷰 5)은 remainingCallBudget·retryAfterSeconds가 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '동시 재변환 한도에 도달했습니다' }), {
        status: 503,
        // `Number.isFinite`는 '1.5'도 통과시키지만 초 단위 헤더는 정수여야 한다.
        headers: { 'Content-Type': 'application/json', 'Retry-After': '1.5' },
      }),
    )

    await expect(
      reconvertUnit('c1', 0, { easy_unit_indexes: [], easy_text_fingerprint: 'a'.repeat(64) }),
    ).rejects.toMatchObject({ status: 503, retryAfterSeconds: null })
  })

  it('빈 문자열 헤더는 0이 아니라 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '동시 재변환 한도에 도달했습니다' }), {
        status: 503,
        // `Number('')`는 0을 낸다 — 빈 문자열을 "없다"가 아니라 "0"으로 잘못 읽으면 안 된다.
        headers: { 'Content-Type': 'application/json', 'Retry-After': '' },
      }),
    )

    await expect(
      reconvertUnit('c1', 0, { easy_unit_indexes: [], easy_text_fingerprint: 'a'.repeat(64) }),
    ).rejects.toMatchObject({ status: 503, retryAfterSeconds: null })
  })
})

describe('createDocumentFromText — 크레딧 헤더(C1/C2)', () => {
  it('402는 X-Credit-Balance·X-Credits-Required를 ApiError.creditBalance·creditsRequired로 읽는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '크레딧이 부족합니다. 상위 플랜을 선택해 주세요.' }), {
        status: 402,
        headers: {
          'Content-Type': 'application/json',
          'X-Credit-Balance': '1',
          'X-Credits-Required': '2',
        },
      }),
    )

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 402,
      creditBalance: 1,
      creditsRequired: 2,
    })
  })

  it('402는 소수 첫째 자리 크레딧 헤더를 그대로 읽는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '크레딧이 부족합니다. 상위 플랜을 선택해 주세요.' }), {
        status: 402,
        headers: {
          'Content-Type': 'application/json',
          'X-Credit-Balance': '0.3',
          'X-Credits-Required': '1.1',
        },
      }),
    )

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 402,
      creditBalance: 0.3,
      creditsRequired: 1.1,
    })
  })

  it('크레딧 헤더가 유한한 십진수가 아니면 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ detail: '크레딧이 부족합니다. 상위 플랜을 선택해 주세요.' }), {
        status: 402,
        headers: {
          'Content-Type': 'application/json',
          'X-Credit-Balance': 'Infinity',
          'X-Credits-Required': 'not-a-number',
        },
      }),
    )

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 402,
      creditBalance: null,
      creditsRequired: null,
    })
  })

  it('헤더가 없는 오류는 creditBalance·creditsRequired가 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(jsonResponse(422, { detail: '글이 너무 깁니다' }))

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 422,
      creditBalance: null,
      creditsRequired: null,
    })
  })

  it('202는 X-Credit-Balance를 creditBalance로 읽는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({
          document_id: 'd1',
          conversion_id: 'c1',
          status: 'pending',
          char_count: 7,
        }),
        {
          status: 202,
          headers: { 'Content-Type': 'application/json', 'X-Credit-Balance': '7' },
        },
      ),
    )

    const result = await createDocumentFromText('본문', null, '제목')

    expect(result.document.conversion_id).toBe('c1')
    expect(result.creditBalance).toBe(7)
  })

  it('X-Credit-Balance가 없는 202는 creditBalance가 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      jsonResponse(202, {
        document_id: 'd1',
        conversion_id: 'c1',
        status: 'pending',
        char_count: 7,
      }),
    )

    const result = await createDocumentFromText('본문', null, '제목')

    expect(result.creditBalance).toBeNull()
  })
})

describe('createDocumentFromText — 개인정보 경고용 검출 헤더', () => {
  it('422는 X-Personal-Data-Kinds를 ApiError.personalDataKinds로 읽는다 — 정렬된 소문자, 쉼표 구분', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ detail: '개인정보로 보이는 내용이 있습니다. 확인 후 다시 시도하세요.' }),
        {
          status: 422,
          headers: {
            'Content-Type': 'application/json',
            'X-Personal-Data-Kinds': 'card,rrn',
          },
        },
      ),
    )

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 422,
      personalDataKinds: ['card', 'rrn'],
    })
  })

  it('헤더에 종류 하나만 실려도 그대로 읽는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ detail: '개인정보로 보이는 내용이 있습니다. 확인 후 다시 시도하세요.' }),
        {
          status: 422,
          headers: { 'Content-Type': 'application/json', 'X-Personal-Data-Kinds': 'rrn' },
        },
      ),
    )

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 422,
      personalDataKinds: ['rrn'],
    })
  })

  it('헤더가 없는 422(다른 갈래)는 personalDataKinds가 null이다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(jsonResponse(422, { detail: '지원 형식: docx, pdf, hwpx, txt' }))

    await expect(createDocumentFromText('본문', null, '제목')).rejects.toMatchObject({
      status: 422,
      personalDataKinds: null,
    })
  })
})
