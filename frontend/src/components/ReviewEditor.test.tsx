import {
  act,
  fireEvent,
  render as renderInDom,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  analyzeReviewSupport,
  ApiError,
  downloadExport,
  getActionGuide,
  getConversion,
  getExplanations,
  getIllustrationPlacements,
  getIllustrations,
  getReviewHistory,
  getReviewSupport,
  listActionGuideJobs,
  putIllustrationPlacements,
  reconvertUnit,
  saveFeedback,
  saveReview,
  updateReviewSupportItem,
  updateReviewSupportItems,
} from '../api/client'
import { lookupTerm } from '../api/dictionary'
import type {
  ConversionResponse,
  FormatPreservation,
  ReconvertUnitResponse,
  ReviewItem,
  ReviewSupportResponse,
} from '../api/types'
import { computeEasyTextFingerprint } from '../review/fingerprint'
import { setUnsavedChanges } from '../review/unsavedChanges'
import {
  conversion,
  segmentMap,
  segmentMapUnit,
  sourceFailed,
  sourceLoading,
  sourceReady,
} from '../test/factories'
import { ReviewEditor } from './ReviewEditor'

/**
 * 라우터 안에서 그린다.
 *
 * 에디터가 품고 있는 피드백 폼이 제출 뒤 변환 기록으로 가는 링크를 내놓으므로,
 * `<Link>`가 라우터 없이는 그려지지 않는다.
 */
function render(ui: ReactElement) {
  return renderInDom(<MemoryRouter>{ui}</MemoryRouter>)
}

/** 문단 대응과 재변환 회귀 검증은 사용자가 상세 비교를 연 상태에서 실행한다. */
function renderDetailed(ui: ReactElement) {
  const result = render(ui)
  const toggle = screen.queryByRole('button', { name: '문단별 상세 비교' })
  if (toggle) fireEvent.click(toggle)
  return result
}

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  saveReview: vi.fn(),
  downloadExport: vi.fn(),
  downloadReviewHistory: vi.fn(),
  saveFeedback: vi.fn(),
  reconvertUnit: vi.fn(),
  getConversion: vi.fn(),
  getActionGuide: vi.fn(),
  getExplanations: vi.fn(),
  getIllustrations: vi.fn(),
  getIllustrationPlacements: vi.fn(),
  putIllustrationPlacements: vi.fn(),
  getReviewHistory: vi.fn(),
  listActionGuideJobs: vi.fn(),
  analyzeReviewSupport: vi.fn(),
  getReviewSupport: vi.fn(),
  updateReviewSupportItem: vi.fn(),
  updateReviewSupportItems: vi.fn(),
}))

vi.mock('../api/dictionary', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/dictionary')>()),
  lookupTerm: vi.fn(),
}))

/**
 * 화면 폭을 고정한다.
 *
 * jsdom에는 matchMedia가 없어서 에디터는 기본값(2열)으로 그려진다. 탭 동작을 보려면
 * "좁은 화면"이라고 답하는 matchMedia를 꽂아야 한다.
 */
function stubViewport(splitView: boolean): void {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: splitView,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }))
}

beforeEach(() => {
  vi.mocked(saveReview).mockReset()
  vi.mocked(downloadExport).mockReset()
  vi.mocked(saveFeedback).mockReset()
  vi.mocked(lookupTerm).mockReset()
  vi.mocked(reconvertUnit).mockReset()
  vi.mocked(getConversion).mockReset()
  vi.mocked(getActionGuide).mockReset()
  vi.mocked(getExplanations).mockReset()
  vi.mocked(getIllustrations).mockReset()
  vi.mocked(getIllustrationPlacements).mockReset()
  vi.mocked(putIllustrationPlacements).mockReset()
  vi.mocked(getReviewHistory).mockReset()
  vi.mocked(listActionGuideJobs).mockReset()
  vi.mocked(analyzeReviewSupport).mockReset()
  vi.mocked(getReviewSupport).mockReset()
  vi.mocked(updateReviewSupportItem).mockReset()
  vi.mocked(updateReviewSupportItems).mockReset()
})

afterEach(() => {
  // 모듈 전역 상태라 테스트끼리 새지 않게 되돌린다(언마운트 정리와 같은 일).
  setUnsavedChanges(false)
  vi.unstubAllGlobals()
})

/**
 * 응답에서 `feedback_submitted_at` 키를 통째로 지운다.
 *
 * 계약은 「키는 늘 있고 값만 null일 수 있다」로 정하지만 그것은 **서버의 약속이지 이
 * 컴포넌트가 받는 값의 보장이 아니다** — 아직 이 필드를 싣지 않는 서버, 배포 시차로
 * 남아 있는 옛 번들에서 키 없이 들어온다. 기본 목(`conversion`)은 계약대로 키를 담아
 * 두고, 그 약속이 깨진 상황은 여기서만 만든다.
 */
function withoutFeedbackKey(response: ConversionResponse): ConversionResponse {
  const stripped: Partial<ConversionResponse> = { ...response }
  delete stripped.feedback_submitted_at
  return stripped as ConversionResponse
}

function reviewItem(overrides: Partial<ReviewItem> = {}): ReviewItem {
  return {
    item_id: 'missing-1',
    kind: 'missing_fact',
    rule_code: 'missing_date',
    source_anchors: [{ source_unit_indexes: [0], quote: '신청은 3월 2일까지입니다.' }],
    easy_unit_indexes: [],
    state: 'needs_review',
    reason: null,
    confirmed_by: null,
    confirmed_at: null,
    ...overrides,
  }
}

function reviewSupportResponse(
  overrides: Partial<ReviewSupportResponse> = {},
): ReviewSupportResponse {
  const relationCodes = [
    'target_scope',
    'all_or_one',
    'exception_scope',
    'deadline_action',
    'amount_subject',
  ]
  return {
    status: 'ready',
    assessment: {
      assessment_id: 'assessment-1',
      content_revision: 1,
      analyzer_version: 'rules-v1',
      review_revision: 1,
      coverage: 'supported',
      limitations: [],
      items: [
        reviewItem(),
        ...relationCodes.map((rule_code, index) =>
          reviewItem({
            item_id: `relation-${index}`,
            kind: 'relation_check',
            rule_code,
            source_anchors: [{ source_unit_indexes: [index], quote: `원문 ${index + 1}` }],
          }),
        ),
      ],
    },
    ...overrides,
  }
}

describe('검수 에디터', () => {
  it('대응표가 있어도 원본과 결과를 각각 한 글상자로 보여주고 줄바꿈을 보존해 저장한다', async () => {
    const user = userEvent.setup()
    const original = '제목\n\n첫 문장\n둘째 문장'
    vi.mocked(saveReview).mockResolvedValue(conversion({ edited_text: `${original}!` }))
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: original, segment_map: segmentMap() })}
        source={sourceReady(original)}
      />,
    )
    const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    expect(editor).toHaveValue(original)
    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue(original)
    expect(screen.queryByLabelText(/쉬운 글 단위/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/원본 1번째 문단/)).not.toBeInTheDocument()
    expect(screen.queryByText('추정')).not.toBeInTheDocument()
    fireEvent.change(editor, { target: { value: `${original}!` } })
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))
    expect(saveReview).toHaveBeenCalledWith('c1', `${original}!`, 1)
  })

  it('전체 편집에서 줄을 추가한 뒤 상세 비교를 열어도 뒤 문단의 대응이 밀리지 않는다', async () => {
    const user = userEvent.setup()
    const original = '첫 문장\n둘째 문장\n마지막 문장'
    render(
      <ReviewEditor
        conversion={conversion({
          easy_text: original,
          segment_map: segmentMap({
            source_unit_count: 3,
            units: [0, 1, 2].map((index) =>
              segmentMapUnit({
                easy_unit_index: index,
                source_unit_indexes: [index],
                confidence: 'high',
              }),
            ),
          }),
        })}
        source={sourceReady(original)}
      />,
    )
    const next = '첫 문장\n둘째\n새 문장\n마지막 문장'
    fireEvent.change(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), {
      target: { value: next },
    })
    await user.click(screen.getByRole('button', { name: '문단별 상세 비교' }))
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫 문장')
    expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('둘째')
    expect(screen.getByLabelText('쉬운 글 단위 3, 대응 확인 불가')).toHaveValue('새 문장')
    expect(screen.getByLabelText('쉬운 글 단위 4, 원본 3번째 문단에 대응')).toHaveValue(
      '마지막 문장',
    )
    await user.click(screen.getByRole('button', { name: '한 문서로 보기' }))
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(next)
  })

  it('저장한 수정본이 있으면 그것을 초기값으로 쓴다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: 'AI 초안입니다.', edited_text: '담당자가 고친 글.' })}
        source={sourceReady('원문입니다.')}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(
      '담당자가 고친 글.',
    )
    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue('원문입니다.')
  })

  it('저장한 수정본이 없으면 AI 초안을 초기값으로 쓴다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: 'AI 초안입니다.', edited_text: null })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue('AI 초안입니다.')
  })

  it('원문을 불러왔으면 왼쪽 패널에 읽기 전용으로 보여준다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('파일에서 뽑은 원문.')} />)

    const panel = screen.getByLabelText('원본 (읽기 전용)')
    expect(panel).toHaveValue('파일에서 뽑은 원문.')
    // 사용자가 고치는 것은 오른쪽 결과다 — 왼쪽은 끝까지 읽기 전용이다.
    expect(panel).toHaveAttribute('readonly')
  })

  /**
   * 원문을 못 가져온 경로에서 왼쪽에 빈 입력칸이 남아 있으면, 화면은 "못 가져왔다"가
   * 아니라 "아직 안 왔다" 또는 "여기에 원문을 적어야 한다"고 말하게 된다. 셋은 서로 다른
   * 상태이므로(DESIGN.md §9) 입력칸이 사라졌는지와 설명이 남았는지를 함께 고정한다.
   */
  it('원문을 못 불러왔으면 빈 입력칸 대신 실패를 설명한다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceFailed('not_found')} />)

    expect(screen.queryByLabelText('원본 (읽기 전용)')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '원문을 불러오지 못함' })).toBeInTheDocument()
    expect(screen.getByText('원문을 찾을 수 없습니다.')).toBeInTheDocument()
    // 「파일로 올려서」가 아니다 — 이제 원문이 없는 이유는 «불러오지 못했다» 하나뿐이다.
    expect(
      screen.queryByText('파일로 올린 문서는 이 화면에서 원문을 다시 표시하지 않습니다.'),
    ).not.toBeInTheDocument()
  })

  /**
   * 불러오는 중에 「원문 없음」을 보여주면 그것은 아직 참이 아닌 문장이다(§9).
   * 로딩과 실패가 같은 화면으로 뭉치는 순간 사용자는 기다리면 될 일을 포기한다.
   */
  it('원문을 불러오는 중에는 없다고 말하지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceLoading()} />)

    expect(screen.getByRole('heading', { name: '원문 불러오는 중' })).toBeInTheDocument()
    expect(screen.getByText('원문을 불러오고 있습니다…')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '원문을 불러오지 못함' })).not.toBeInTheDocument()
    expect(screen.queryByText('원문을 찾을 수 없습니다.')).not.toBeInTheDocument()
  })

  it('네트워크 실패에는 다시 불러올 행동을 주고, 404에는 주지 않는다', async () => {
    const user = userEvent.setup()
    const retry = vi.fn()
    const view = render(
      <ReviewEditor conversion={conversion()} source={sourceFailed('unreachable', retry)} />,
    )

    expect(screen.getByText('원문을 불러오지 못했습니다.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '원문 다시 불러오기' }))
    expect(retry).toHaveBeenCalledTimes(1)

    // 404는 다시 물어도 404다 — 눌러도 소용없는 행동을 제시하지 않는다(§15).
    view.rerender(
      <MemoryRouter>
        <ReviewEditor conversion={conversion()} source={sourceFailed('not_found')} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: '원문 다시 불러오기' })).not.toBeInTheDocument()
  })

  it('AI 초안임을 알리는 배너를 보여준다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    expect(screen.getByRole('note')).toHaveTextContent('AI가 만든 초안입니다')
  })

  it('수정하면 저장 안 됨이 되고, 저장하면 저장한 시각을 남긴다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    expect(screen.getByRole('status')).toHaveTextContent('저장 전')

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')

    expect(screen.getByRole('status')).toHaveTextContent('저장 안 됨')

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    // 저장 여부는 토스트로 흘려보내지 않고 화면에 남는다(§9).
    expect(await screen.findByText(/^저장됨 · /)).toBeInTheDocument()
    expect(screen.queryByText('저장 안 됨')).not.toBeInTheDocument()
  })

  it('저장했다는 사실을 두 곳에서 낭독하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    const success = await screen.findByText('검수 내용을 저장했습니다.')
    // 낭독되는 곳은 저장 상태 라벨 하나뿐이고, 버튼 옆 성공 안내는 눈으로만 본다.
    const announced = screen.getAllByRole('status')
    expect(announced).toHaveLength(1)
    expect(announced[0]).toHaveTextContent(/^저장됨 · /)
    expect(success).not.toHaveAttribute('role')
  })

  /*
    의견을 보낸 변환에서 상단이 「아직 저장한 검수 내용이 없습니다」만 말하면, 서버에
    잘 저장된 제출을 사용자가 실패로 읽는다. 두 사실은 서로를 지우지 않는다 — 수정본을
    저장하지 않은 것도 참이고, 의견을 보낸 것도 참이라 둘 다 적혀 있어야 한다.
  */
  it('의견을 보낸 변환은 저장 상태와 의견 보냄을 함께 적는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          reviewed_at: null,
          feedback_submitted_at: '2026-08-27T02:00:00Z',
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('저장 전')
    expect(screen.getByText(/고쳐서 저장한 내용은 없습니다/)).toBeInTheDocument()
    // 「아직 …이 없습니다」는 할 일이 남았다는 말로 읽혀 제출 실패로 오해된다.
    expect(screen.queryByText(/아직 저장한 검수 내용이 없습니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/^의견 보냄 · /)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '의견 보내기' })).not.toBeInTheDocument()
  })

  it('의견을 보낸 적이 없으면 의견 보냄을 적지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    expect(
      screen.getByText('아직 저장한 검수 내용이 없습니다. AI 초안 그대로입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/의견 보냄/)).not.toBeInTheDocument()
  })

  /*
    키가 없으면 「제출 안 함」으로 읽는다.

    `=== null`로 물으면 `undefined`가 그 갈래를 비켜 가, 아무 의견도 보내지 않은 변환에
    「의견 보냄 · Invalid Date」 배지가 뜬다. `new Date(undefined)`는 던지지 않고 Invalid
    Date를 만들기 때문에 화면이 조용히 거짓말을 한다 — 값이 없을 때 안전한 오답은
    「아직 안 보냈다」 쪽이다.
  */
  it('의견 제출 시각 키가 아예 없으면 제출 안 한 것으로 읽는다', () => {
    render(
      <ReviewEditor
        conversion={withoutFeedbackKey(conversion({ reviewed_at: null }))}
        source={sourceFailed()}
      />,
    )

    expect(
      screen.getByText('아직 저장한 검수 내용이 없습니다. AI 초안 그대로입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/의견 보냄/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Invalid Date/)).not.toBeInTheDocument()
  })

  /*
    토스트만 뜨고 상단이 그대로면 화면은 방금 일어난 일을 반영하지 않은 것이다.
    다시 조회하지 않고 서버가 응답에 실어 준 시각을 그대로 옮긴다.
  */
  it('의견을 보내면 상단 상태가 곧바로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveFeedback).mockResolvedValue({
      conversion_id: 'c1',
      publish_intent: 'with_edits',
      quality_score: 4,
      minutes_spent: 25,
      comment: null,
      submitted_at: '2026-08-27T02:00:00Z',
    })
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    expect(screen.queryByText(/의견 보냄/)).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('조금 고쳐서 쓰겠다'))
    await user.click(screen.getByLabelText('4점'))
    await user.type(screen.getByLabelText('이번 건 소요 시간(분)'), '25')
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(await screen.findByText(/^의견 보냄 · /)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '의견 보내기' })).not.toBeInTheDocument()
    expect(screen.getByText('의견을 보냈습니다. 감사합니다.').parentElement).toHaveFocus()
    // 화면을 대신 넘기지 않는다 — 검수 화면은 그대로 있고 돌아가는 길만 생긴다.
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '변환 기록으로 돌아가기' })).toBeInTheDocument()
    // 제출 성공 안내로 초점이 이동한다. 상태 패널이 같은 사실을 한 번 더 읽지 않게
    // 이 배지는 `role="status"` 바깥에 있다(§11 중복 낭독 금지).
    const announced = screen.getAllByRole('status').map((node) => node.textContent ?? '')
    expect(announced.filter((text) => text.includes('의견 보냄'))).toHaveLength(0)
  })

  /*
    저장 버튼은 진행 중에 `disabled` 가 된다. 브라우저는 초점을 가진 요소가 잠기는 순간
    초점을 `<body>` 로 떨어뜨리므로, 되돌려 놓지 않으면 키보드 사용자는 저장 한 번에
    탭 경로를 통째로 잃고 문서 맨 앞에서 다시 밟아야 한다(§14).
  */
  it('저장이 끝나면 초점이 저장 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    const save = screen.getByRole('button', { name: '검수 내용 저장' })
    await user.click(save)

    await screen.findByText('검수 내용을 저장했습니다.')
    expect(screen.getByRole('button', { name: '검수 내용 저장' })).toHaveFocus()
  })

  it('내려받기가 끝나도 초점이 그 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['쉬운 글'], { type: 'text/plain' }),
      filename: '쉬운 글.txt',
    })
    // jsdom 에는 blob URL 도 anchor 내려받기도 없다 — 저장 경로만 통과시킨다.
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: () => 'blob:test',
      revokeObjectURL: () => undefined,
    })
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    await user.click(screen.getByRole('button', { name: 'TXT로 내려받기' }))

    await screen.findByText('TXT 파일을 내려받았습니다.')
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toHaveFocus()
  })

  it('수정한 글을 저장하고 결과를 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '고친 글.', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    await user.clear(editor)
    await user.type(editor, '고친 글.')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(vi.mocked(saveReview)).toHaveBeenCalledWith('c1', '고친 글.', 1)
    expect(await screen.findByText('검수 내용을 저장했습니다.')).toBeInTheDocument()
  })

  it('revision 충돌이면 현재 내용을 복사하고 최신 저장본을 불러와 다시 저장한다', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    vi.mocked(saveReview)
      .mockRejectedValueOnce(new ApiError(409, '다른 화면에서 본문이 바뀌었습니다'))
      .mockResolvedValueOnce(conversion({ edited_text: '최신 내용 추가', content_revision: 3 }))
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ edited_text: '최신 내용', content_revision: 2 }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    await user.type(editor, ' 덧붙임')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('다른 화면에서 본문이 바뀌었습니다')
    expect(editor).toHaveValue('초안. 덧붙임')
    expect(screen.getByRole('status')).toHaveTextContent('저장 안 됨')

    await user.click(screen.getByRole('button', { name: '현재 편집 내용 복사' }))
    expect(writeText).toHaveBeenCalledWith('초안. 덧붙임')

    await user.click(screen.getByRole('button', { name: '최신 내용 불러오기' }))
    expect(getConversion).toHaveBeenCalledWith('c1')
    expect(editor).toHaveValue('최신 내용')

    await user.type(editor, ' 추가')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))
    expect(saveReview).toHaveBeenLastCalledWith('c1', '최신 내용 추가', 2)
  })

  it.each(['docx', 'hwpx', 'txt'] as const)(
    '%s 내려받기를 누르면 파일을 받아 저장한다',
    async (format) => {
      const user = userEvent.setup()
      const objectUrl = 'blob:test'
      const createObjectURL = vi.fn(() => objectUrl)
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
      vi.mocked(downloadExport).mockResolvedValue({
        blob: new Blob(['내용']),
        filename: `재난지원금 안내.${format}`,
      })
      render(
        <ReviewEditor conversion={conversion({ export_format: format })} source={sourceFailed()} />,
      )

      await user.click(screen.getByRole('button', { name: `${format.toUpperCase()}로 내려받기` }))

      expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', format)
      expect(click).toHaveBeenCalled()
      expect(revokeObjectURL).toHaveBeenCalledWith(objectUrl)
      expect(
        await screen.findByText(`${format.toUpperCase()} 파일을 내려받았습니다.`),
      ).toBeInTheDocument()

      click.mockRestore()
      vi.unstubAllGlobals()
    },
  )

  it('서버가 정한 형식 하나만 내려받기로 제시한다 — 교차 형식 버튼을 그리지 않는다', () => {
    render(
      <ReviewEditor conversion={conversion({ export_format: 'docx' })} source={sourceFailed()} />,
    )

    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    // 원본이 DOCX인데 txt·hwpx 버튼을 그리면 그 버튼은 서버에서 반드시 409로 실패한다.
    expect(screen.queryByRole('button', { name: 'TXT로 내려받기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'HWPX로 내려받기' })).not.toBeInTheDocument()
  })

  it('PDF 원본은 기본 형식인 TXT 내려받기만 제시한다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: 'txt' })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'DOCX로 내려받기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'HWPX로 내려받기' })).not.toBeInTheDocument()
  })

  it('구버전 응답에 내려받을 수단이 없으면 내려받기 행동을 제시하지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: null })}
        source={sourceFailed()}
      />,
    )

    // §6.5 — 상태를 지어내지 않는다. 저장은 여전히 할 수 있어야 한다.
    expect(screen.getByRole('button', { name: '검수 내용 저장' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /내려받기$/ })).not.toBeInTheDocument()
  })

  it('구버전 PDF 응답에 선택지가 있으면 형식마다 버튼을 하나씩 그린다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'pdf',
          export_format: null,
          export_format_choices: ['docx', 'hwpx'],
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'HWPX로 내려받기' })).toBeInTheDocument()
    // 선택지에 없는 형식의 버튼은 그리지 않는다.
    expect(screen.queryByRole('button', { name: 'TXT로 내려받기' })).not.toBeInTheDocument()
  })

  it.each(['docx', 'hwpx'] as const)(
    'PDF 선택지 중 %s를 누르면 그 형식으로 내려받는다',
    async (format) => {
      const user = userEvent.setup()
      const objectUrl = 'blob:test'
      const createObjectURL = vi.fn(() => objectUrl)
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
      vi.mocked(downloadExport).mockResolvedValue({
        blob: new Blob(['내용']),
        filename: `재난지원금 안내.${format}`,
      })
      render(
        <ReviewEditor
          conversion={conversion({
            source_format: 'pdf',
            export_format: null,
            export_format_choices: ['docx', 'hwpx'],
          })}
          source={sourceFailed()}
        />,
      )

      await user.click(screen.getByRole('button', { name: `${format.toUpperCase()}로 내려받기` }))

      expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', format)
      expect(click).toHaveBeenCalled()

      click.mockRestore()
      vi.unstubAllGlobals()
    },
  )

  describe('좁은 화면', () => {
    it('원문이 있으면 원문·쉬운 글 탭으로 나누고 키보드로 옮길 수 있다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} source={sourceReady('원문입니다.')} />)

      expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['원문', '쉬운 글'])
      expect(screen.getByLabelText('원본 (읽기 전용)')).toBeVisible()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).not.toBeVisible()

      screen.getByRole('tab', { name: '원문' }).focus()
      await user.keyboard('{ArrowRight}')

      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
      expect(screen.getByLabelText('원본 (읽기 전용)')).not.toBeVisible()
    })

    it('원문을 아직 못 받았으면 탭을 만들지 않고 그 사실을 그대로 보여준다', () => {
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} source={sourceLoading()} />)

      // 「불러오는 중」을 탭 뒤에 숨기면 그 사실이 사용자에게 닿지 않는다(§9).
      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      expect(screen.getByRole('heading', { name: '원문 불러오는 중' })).toBeInTheDocument()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
    })

    it('원문을 못 불러왔으면 탭 없이 실패를 그대로 보여준다', () => {
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} source={sourceFailed('not_found')} />)

      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      expect(screen.getByText('원문을 찾을 수 없습니다.')).toBeInTheDocument()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
    })

    /**
     * 늦게 도착한 원문이 **편집 중인 화면을 가로채지 않는다.**
     *
     * 탭이 없는 동안에는 두 패널이 함께 보이므로 사용자는 결과 편집기에 바로 타이핑한다.
     * 그때 원문이 도착해 탭이 생기면서 활성 탭이 초기값 `원문`에 머무르면, 방금까지 고치던
     * 글과 초점이 눈앞에서 사라진다. 네트워크가 느릴수록 더 오래 타이핑하다 당한다.
     */
    it('원문이 늦게 도착해 탭이 생겨도 편집 중이던 결과 패널을 빼앗지 않는다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceLoading()} />)

      // 아직 탭이 없다 — 두 패널이 위아래로 모두 보인다.
      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
      await user.click(editor)
      await user.type(editor, ' 수정')
      expect(editor).toHaveFocus()

      // 원문 도착 — 여기서 탭이 생긴다.
      view.rerender(
        <MemoryRouter>
          <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
        </MemoryRouter>,
      )

      expect(screen.getAllByRole('tab')).toHaveLength(2)
      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(editor).toBeVisible()
      expect(editor).toHaveValue('초안. 수정')
      expect(editor).toHaveFocus()
    })

    it('아무것도 건드리지 않았다면 탭이 생길 때 원문이 먼저다', () => {
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceLoading()} />)

      view.rerender(
        <MemoryRouter>
          <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
        </MemoryRouter>,
      )

      // §11의 읽기 순서는 그대로다 — 원문 다음 결과.
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByLabelText('원본 (읽기 전용)')).toBeVisible()
    })

    /**
     * 전이할 때만 판정한다. 사용자가 원문 탭을 직접 고른 뒤 창 크기가 오갔다고 결과로
     * 튕기면 그것도 같은 종류의 가로채기다.
     */
    it('탭이 사라졌다 다시 생겨도 사용자가 고른 탭을 덮어쓰지 않는다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />)

      // 고쳐 두고(=dirty) 원문 탭을 직접 고른다.
      await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
      await user.click(screen.getByRole('tab', { name: '원문' }))
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')

      // 넓어졌다가(탭 사라짐) 다시 좁아진다(탭 생김).
      const rerender = (splitView: boolean) => {
        stubViewport(splitView)
        view.rerender(
          <MemoryRouter>
            <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
          </MemoryRouter>,
        )
      }
      rerender(true)
      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      rerender(false)

      // 초점은 어느 입력칸에도 없지만 고친 내용은 있다 — 그래도 사용자가 마지막으로
      // 고른 원문 탭이 이긴다.
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')
    })

    /**
     * **초점은 과거의 탭 선택보다 강한 신호다.**
     *
     * 탭을 골라 둔 사람이 화면을 넓혀(탭 소멸) 결과를 고치다가 다시 좁히면, 그 선택을
     * 존중한다는 이유로 편집 중이던 결과 패널이 숨는다 — 처음에 고친 것과 같은 버그가
     * 한 단계 뒤에 남아 있던 자리다.
     */
    it('탭을 골라 둔 뒤라도 넓혔다 좁힐 때 초점이 있는 패널을 빼앗지 않는다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />)
      const rerender = (splitView: boolean) => {
        stubViewport(splitView)
        view.rerender(
          <MemoryRouter>
            <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
          </MemoryRouter>,
        )
      }

      // ① 원문 탭을 직접 고른다.
      await user.click(screen.getByRole('tab', { name: '원문' }))
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')

      // ② 넓힌다 — 탭이 사라지고 두 패널이 모두 보인다.
      rerender(true)
      expect(screen.queryAllByRole('tab')).toHaveLength(0)

      // ③ 결과 편집기에 초점을 두고 타이핑한다.
      const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
      await user.click(editor)
      await user.type(editor, ' 수정')
      expect(editor).toHaveFocus()

      // ④ 다시 좁힌다 — 여기서 결과 패널이 숨으면 안 된다.
      rerender(false)

      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(editor).toBeVisible()
      expect(editor).toHaveValue('초안. 수정')
      expect(editor).toHaveFocus()
    })
  })
})

/**
 * jsdom 에는 blob URL 도 anchor 내려받기도 없다 — 저장 경로만 통과시킨다.
 * 되돌리기는 전역 afterEach 의 `vi.unstubAllGlobals()` 가 맡는다.
 */
function stubBlobSaving(): void {
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: () => 'blob:test',
    revokeObjectURL: () => undefined,
  })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
}

describe('원본 서식 유지 패널', () => {
  it.each([
    ['docx', 'DOCX'],
    ['hwpx', 'HWPX'],
  ] as const)('%s 원본이면 패널을 그리고 형식을 밝힌다', (sourceFormat, label) => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: sourceFormat,
          export_format: sourceFormat,
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText(label)).toBeInTheDocument()
    expect(within(panel).getByText('유지 가능')).toBeInTheDocument()
  })

  /** §6.5 표 — 붙여넣기는 「적용 대상 아님」이라 패널 자체가 없다. */
  it('붙여넣기(TXT)에는 패널을 그리지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문입니다.')} />)

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
  })

  /**
   * 상태를 색으로만 가르지 않는다(§8.1) — 상태마다 **문구가 다르다.** 아이콘도 다른 것을
   * 쓰지만, 낭독기와 흑백 화면에서 남는 단서는 이 라벨이다.
   */
  it.each<[FormatPreservation, string]>([
    [{ status: 'available', details: [] }, '유지 가능'],
    [{ status: 'partial', details: ['표 1개는 단순 표로 바뀝니다.'] }, '일부 유지'],
    [{ status: 'failed', details: ['원본 파일을 열 수 없습니다.'] }, '서식 유지 실패'],
  ])('상태 %o 를 색이 아닌 문구로 구분한다', (preservation, label) => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: preservation,
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText(label)).toBeInTheDocument()
  })

  /**
   * 유지할 원본이 없다는 판정(`not_applicable`)에는 아무 말도 하지 않는다. 없는 원본을
   * 두고 상태를 말하는 것은 사용자가 할 일이 없는 정보를 화면에 세우는 일이다.
   */
  it('유지할 원본이 없으면(not_applicable) 상태 표시를 그리지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'not_applicable', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
  })

  /**
   * `일부 유지`는 **정상 결과다.** 이 제품이 약속하는 유지 수준은 「심하게 틀어지지 않는
   * 정도」이고 문단 수가 어긋나는 것은 예상된 결과다 — 다시 시도해도 같은 답이 온다.
   * 조치할 수 없는 정보를 경고색·경고 아이콘으로 그리면 소음이고, 정작 조치가 필요한
   * `failed`의 강조가 묻힌다.
   */
  it('일부 유지를 경고처럼 그리지 않는다 — 강조는 실패에만 있다', () => {
    const { unmount } = render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'partial', details: ['문단 3개는 본문 끝에 덧붙습니다.'] },
        })}
        source={sourceFailed()}
      />,
    )

    const partial = within(screen.getByRole('region', { name: '원본 서식 유지' })).getByText(
      '일부 유지',
    )
    expect(partial.className).not.toMatch(/warning|danger/)
    // 경고가 아니므로 낭독기를 끊는 알림도 아니고, 다시 시도하라는 안내도 붙지 않는다.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/다시 눌러 시도할 수 있고/)).not.toBeInTheDocument()
    unmount()

    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'failed', details: ['원본 파일을 열 수 없습니다.'] },
        })}
        source={sourceFailed()}
      />,
    )

    const failed = within(screen.getByRole('region', { name: '원본 서식 유지' })).getByText(
      '서식 유지 실패',
    )
    expect(failed.className).toMatch(/danger/)
  })

  /** `partial`의 내용은 서버 문구가 정본이다 — 화면이 개수를 다시 세거나 문장을 짓지 않는다. */
  it('일부 유지에서는 서버가 준 항목을 그대로 나열한다', () => {
    const details = [
      '머리말·꼬리말 2곳은 원본 문구를 그대로 둡니다.',
      '문단 3개는 원본에 자리가 없어 본문 끝에 덧붙습니다.',
    ]
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'partial', details },
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    for (const detail of details) {
      expect(within(panel).getByText(detail)).toBeInTheDocument()
    }
  })

  /**
   * §6.5 — 서식을 유지할 수 없을 때 텍스트 전용 파일로 조용히 대체하지 않는다. 사유와
   * 다시 시도 행동을 보이고, 내려받기 버튼은 그 「다시 시도」로 그 자리에 남는다.
   */
  it('서식 유지 실패는 사유와 다시 할 수 있는 일을 보이되 다른 형식으로 우회하지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: {
            status: 'failed',
            details: ['원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다.'],
          },
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(
      within(panel).getByText('원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다.'),
    ).toBeInTheDocument()
    expect(within(panel).getByText(/다시 눌러 시도할 수 있고/)).toBeInTheDocument()
    // 같은 형식으로 다시 시도하는 길만 남는다 — txt로 대신 받는 버튼을 만들지 않는다.
    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /TXT/ })).not.toBeInTheDocument()
  })

  /**
   * 서버가 아직 판정하지 않은 상태(null)를 「유지 가능」으로 낙관하지 않는다(§6.5).
   * 계약에 `checking`이 없으므로 진행 표시(스피너)도 만들지 않는다.
   */
  it('판정이 아직 없으면 확인되지 않음으로 두고 진행 표시를 만들지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: null,
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('확인되지 않음')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).not.toBeInTheDocument()
    expect(within(panel).queryByRole('status')).not.toBeInTheDocument()
  })

  /**
   * PDF는 §6.5 표에서 서식 유지 패널의 대상이 아니다. 배포 시차로 남은 구버전 응답에
   * 내려받기 버튼이 없으면 그 이유를 말한다.
   */
  it('구버전 PDF 응답에 선택지가 없으면 내려받기가 없는 이유를 말한다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: null })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByText(/PDF는 출력용 형식이라/)).toBeInTheDocument()
    expect(screen.getByText(/업로드와 변환, 검수와 저장은 그대로 됩니다/)).toBeInTheDocument()
    // 곧 될 것처럼 적지 않는다 — 하지 않기로 정해진 범위다.
    expect(screen.queryByText(/준비 중/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /내려받기$/ })).not.toBeInTheDocument()
  })

  it('PDF 원본을 TXT로 받을 때 레이아웃과 스타일이 유지되지 않음을 말한다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: 'txt' })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByText(/원본 레이아웃과 스타일은 유지되지 않습니다/)).toBeInTheDocument()
    expect(screen.getByText(/검수한 내용만 TXT로 내려받습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toBeInTheDocument()
  })

  /**
   * 2.6.0 — PDF에 선택지가 있으면(`export_format_choices`) 「내려받기가 없다」는
   * 옛 문구를 더는 쓰지 않는다. 버튼이 실제로 있으므로 그 자리에는 무엇이 나오는지만
   * 말한다: 원본 레이아웃은 반영되지 않는 새 문서라는 사실.
   */
  it('PDF 원본에 선택지가 있으면 내려받기가 없다는 문구 대신 새 문서라는 안내를 보여준다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'pdf',
          export_format: null,
          export_format_choices: ['docx', 'hwpx'],
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByText(/원본 레이아웃을 그대로 유지할 수 없습니다/)).toBeInTheDocument()
    expect(screen.getByText(/새 문서를 만들어 드립니다/)).toBeInTheDocument()
    // 더는 참이 아닌 옛 문구를 남기지 않는다 — 버튼이 실제로 있다.
    expect(screen.queryByText(/이 문서에는\s*내려받기가 없습니다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/준비 중/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'HWPX로 내려받기' })).toBeInTheDocument()
  })

  /**
   * 판정은 **검수본의 문단 수**에서 나온다 — 원본 구조 단위와 짝이 맞으면 `available`,
   * 어긋나면 `partial`이다. 그래서 담당자가 검수하며 문단을 나누면 **서버의 답이 바뀐다.**
   *
   * 저장 응답은 `GET`과 같은 스키마라 그 새 판정을 싣고 온다. 화면이 조회 때 받은 값을
   * 붙들고 있으면 패널이 「유지 가능」이라고 말한 뒤 그와 다른 파일이 내려간다 — §6.5의
   * 「상태는 낙관적으로 추측하지 않는다. 서버가 제공한 결과만 표시한다」를 어기는 자리다.
   */
  it('문단을 나눠 저장하면 서버가 새로 잰 판정으로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        source_format: 'docx',
        export_format: 'docx',
        edited_text: '첫 문단\n둘째 문단',
        reviewed_at: '2026-08-07T02:00:00Z',
        format_preservation: {
          status: 'partial',
          details: ['문단 1개는 원본에 자리가 없어 본문 끝에 덧붙습니다.'],
        },
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          easy_text: '첫 문단',
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    expect(
      within(screen.getByRole('region', { name: '원본 서식 유지' })).getByText('유지 가능'),
    ).toBeInTheDocument()

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), '{enter}둘째 문단')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    await screen.findByText('검수 내용을 저장했습니다.')
    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('일부 유지')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).toBeNull()
    expect(
      within(panel).getByText('문단 1개는 원본에 자리가 없어 본문 끝에 덧붙습니다.'),
    ).toBeInTheDocument()
  })

  /**
   * 계약은 `format_preservation` 키가 **늘 있고 값이 `null`일 수 있다**고 정한다. 저장
   * 응답의 `null`도 서버의 답(「아직 판정하지 않았다」)이므로 지난 조회의 판정으로 메우지
   * 않는다 — `??`로 옛 값을 붙들면 화면이 서버가 하지 않은 말을 하게 된다.
   */
  it('저장 응답의 판정이 null이면 옛 판정을 붙들지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        source_format: 'docx',
        export_format: 'docx',
        edited_text: '초안. 수정',
        reviewed_at: '2026-08-07T02:00:00Z',
        format_preservation: null,
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          easy_text: '초안.',
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    await screen.findByText('검수 내용을 저장했습니다.')
    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('확인되지 않음')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).toBeNull()
  })
})

describe('저장하고 내려받기', () => {
  /**
   * §6.5 「한 번의 명확한 행동」 갈래도 저장을 지난다 — 그러므로 판정도 같이 갱신된다.
   * 이 경로에서 갱신이 빠지면 사용자가 방금 받은 파일과 화면의 약속이 어긋난 채로 남는다.
   */
  it('저장하고 내려받기에서도 판정이 갱신된다', async () => {
    const user = userEvent.setup()
    stubBlobSaving()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        source_format: 'docx',
        export_format: 'docx',
        edited_text: '첫 문단\n둘째 문단',
        reviewed_at: '2026-08-07T02:00:00Z',
        format_preservation: {
          status: 'partial',
          details: ['문단 1개는 원본에 자리가 없어 본문 끝에 덧붙습니다.'],
        },
      }),
    )
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '안내문-쉬운글.docx',
    })
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          easy_text: '첫 문단',
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), '{enter}둘째 문단')
    await user.click(screen.getByRole('button', { name: '저장하고 DOCX로 내려받기' }))

    await screen.findByText('검수 내용을 저장하고 DOCX 파일을 내려받았습니다.')
    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('일부 유지')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).toBeNull()
  })

  /** §6.5 — 저장하지 않은 수정이 있으면 두 걸음을 한 번의 행동으로 제공한다. */
  it('저장하지 않은 수정이 있으면 버튼이 저장까지 한다고 말하고 실제로 저장한 뒤 내려받는다', async () => {
    const user = userEvent.setup()
    stubBlobSaving()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '안내문-쉬운글.txt',
    })
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')

    await user.click(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' }))

    expect(vi.mocked(saveReview)).toHaveBeenCalledWith('c1', '초안. 수정', 1)
    expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', 'txt')
    expect(
      await screen.findByText('검수 내용을 저장하고 TXT 파일을 내려받았습니다.'),
    ).toBeInTheDocument()
    // 저장까지 끝났으므로 버튼은 다시 한 걸음짜리로 돌아간다.
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toBeInTheDocument()
  })

  it('저장할 것이 없으면 버튼은 내려받기만 말하고 저장을 부르지 않는다', async () => {
    const user = userEvent.setup()
    stubBlobSaving()
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '안내문-쉬운글.txt',
    })
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    await user.click(screen.getByRole('button', { name: 'TXT로 내려받기' }))

    expect(vi.mocked(saveReview)).not.toHaveBeenCalled()
    expect(await screen.findByText('TXT 파일을 내려받았습니다.')).toBeInTheDocument()
  })

  /**
   * §9 — 어느 걸음에서 멈췄는지, 무엇이 남아 있는지, 다음에 무엇을 할지가 갈래마다 다르다.
   * 저장부터 실패했으면 **파일을 만들지 않았다**는 사실까지 말해야 한다.
   */
  it('저장에 실패하면 내려받지 않고 그 사실을 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockRejectedValue(new ApiError(409, '아직 완료되지 않은 변환입니다'))
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('내려받지 않았습니다')
    expect(alert).toHaveTextContent('아직 완료되지 않은 변환입니다')
    expect(alert).toHaveTextContent('고친 내용은 화면에 그대로 있습니다')
    expect(vi.mocked(downloadExport)).not.toHaveBeenCalled()
    // 저장이 안 됐으므로 화면은 여전히 「저장 안 됨」이고 버튼도 두 걸음짜리 그대로다.
    expect(screen.getByRole('status')).toHaveTextContent('저장 안 됨')
    expect(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' })).toBeInTheDocument()
  })

  it('저장은 됐는데 내려받기가 실패하면 저장된 사실과 다음 행동을 구분해 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    vi.mocked(downloadExport).mockRejectedValue(new ApiError(409, '아직 완료되지 않았습니다'))
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('검수 내용은 저장했습니다')
    expect(alert).toHaveTextContent('아직 완료되지 않았습니다')
    expect(alert).toHaveTextContent('내려받기를 다시 눌러 주세요')
    // 저장은 실제로 끝났다 — 그 사실이 상태 라벨에도 남는다(§9).
    expect(await screen.findByText(/^저장됨 · /)).toBeInTheDocument()
  })
})

/**
 * 문단 단위 대응(`segment_map`, 계약 2.12.0) — 계획 §6 S3.
 *
 * 기본 목(`conversion()`)의 `segment_map`은 `null`이라 위 모든 테스트는 옛 단일
 * 에디터 경로를 그대로 탄다. 여기서는 `segment_map`을 명시로 채운 변환만 다룬다.
 */
describe('문단 단위 대응(segment_map)', () => {
  it('segment_map이 null이면 옛 단일 에디터를 그대로 쓴다', () => {
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ segment_map: null, easy_text: '첫 문단\n둘째 문단' })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(
      '첫 문단\n둘째 문단',
    )
    expect(screen.queryByLabelText(/^쉬운 글 단위 1,/)).not.toBeInTheDocument()
  })

  it('HIGH 대응은 「대응 확인」, LOW는 「추정」 배지로 구분하고 각 단위를 textarea로 그린다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [], confidence: 'low' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 문단 하나\n원본 문단 둘')}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫 문장')
    expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('둘째 문장')
    expect(screen.getByLabelText('원본 1번째 문단')).toHaveValue('원본 문단 하나')
    expect(screen.getByLabelText('원본 2번째 문단')).toHaveValue('원본 문단 둘')
    expect(screen.getByText('대응 확인')).toBeInTheDocument()
    expect(screen.getByText('추정')).toBeInTheDocument()
  })

  it('쉬운 글 단위를 hover·focus하면 대응하는 원본 단위가 밝혀지고, 반대 방향도 같다', async () => {
    const user = userEvent.setup()
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 하나\n원본 둘')}
      />,
    )

    const easyUnit1 = screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')
    const sourceUnit1 = screen.getByLabelText('원본 1번째 문단')
    const sourceUnit2 = screen.getByLabelText('원본 2번째 문단')

    expect(sourceUnit1.className).not.toMatch(/border-primary/)
    await user.click(easyUnit1)
    expect(sourceUnit1.className).toMatch(/border-primary/)
    expect(sourceUnit2.className).not.toMatch(/border-primary/)

    await user.tab() // easyUnit1에서 초점을 떼어 하이라이트를 지운다.
    expect(sourceUnit1.className).not.toMatch(/border-primary/)

    // 반대 방향 — 원본 단위를 hover·focus하면 대응하는 쉬운 글 단위가 밝혀진다.
    await user.click(sourceUnit2)
    const easyUnit2 = screen.getByLabelText('쉬운 글 단위 2, 원본 2번째 문단에 대응')
    expect(easyUnit2.className).toMatch(/border-primary/)
  })

  it('LOW 대응은 hover해도 원본 단위를 밝히지 않는다', async () => {
    const user = userEvent.setup()
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'low' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '문장 하나', segment_map: map })}
        source={sourceReady('원본 하나')}
      />,
    )

    const easyUnit = screen.getByLabelText('쉬운 글 단위 1, 대응 확인 불가')
    const sourceUnit = screen.getByLabelText('원본 1번째 문단')
    await user.click(easyUnit)
    expect(sourceUnit.className).not.toMatch(/border-primary/)
  })

  it('단위 안에서 Enter를 누르면 그 자리를 나누고 뒤 단위 번호가 밀린다', async () => {
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1)
    fireEvent.keyDown(unit, { key: 'Enter' })

    // 나뉜 두 단위 모두 원래 단위의 대응(high, 원본 1번째 문단)을 그대로 물려받는다 —
    // 국소 재계산은 서버가 다시 잴 때까지의 최선 추정이다(계획 §6 S3).
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫')
    expect(screen.getByLabelText('쉬운 글 단위 2, 원본 1번째 문단에 대응')).toHaveValue('줄')
  })

  it('분할 뒤 저장하면 무손실로 이은 문자열 하나가 그대로 서버로 간다', async () => {
    const user = userEvent.setup()
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        edited_text: '첫\n줄',
        reviewed_at: '2026-08-07T02:00:00Z',
        segment_map: map,
      }),
    )
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1)
    fireEvent.keyDown(unit, { key: 'Enter' })

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    // split('\n') ↔ join('\n') 왕복 — 화면이 단위 목록으로 바뀌어도 저장 계약은
    // `updateConversion` 하나뿐이고 값은 그대로 이은 문자열이다(계획 §2).
    expect(vi.mocked(saveReview)).toHaveBeenCalledWith('c1', '첫\n줄', 1)
  })

  it('맨 앞에서 Backspace를 누르면 앞 단위와 합치고, 둘 다 high일 때만 합친 단위도 high다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'low' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄\n둘째줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit2 = screen.getByLabelText(/^쉬운 글 단위 2,/) as HTMLTextAreaElement
    unit2.focus()
    unit2.setSelectionRange(0, 0)
    fireEvent.keyDown(unit2, { key: 'Backspace' })

    // 둘 중 하나가 low였으므로 합친 단위도 low다 — 「대응 확인 불가」로 낮춰 안전하게 그린다.
    expect(screen.getByLabelText('쉬운 글 단위 1, 대응 확인 불가')).toHaveValue('첫줄둘째줄')
    expect(screen.queryByLabelText(/^쉬운 글 단위 2,/)).not.toBeInTheDocument()
  })

  it('화살표 위·아래로 단위 사이를 옮긴다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄\n둘째줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit1 = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    const unit2 = screen.getByLabelText(/^쉬운 글 단위 2,/) as HTMLTextAreaElement

    unit1.focus()
    unit1.setSelectionRange(unit1.value.length, unit1.value.length)
    fireEvent.keyDown(unit1, { key: 'ArrowDown' })
    expect(unit2).toHaveFocus()

    unit2.setSelectionRange(0, 0)
    fireEvent.keyDown(unit2, { key: 'ArrowUp' })
    expect(unit1).toHaveFocus()
  })

  it('단위가 200개를 넘으면 제공할 수 없는 상세 비교 버튼을 노출하지 않는다', () => {
    const unitCount = 201
    const bigText = Array.from({ length: unitCount }, (_, index) => `문장 ${index}`).join('\n')
    const map = segmentMap({
      source_unit_count: unitCount,
      units: Array.from({ length: unitCount }, (_, index) =>
        segmentMapUnit({
          easy_unit_index: index,
          source_unit_indexes: [index],
          confidence: 'high',
        }),
      ),
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: bigText, segment_map: map })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('button', { name: '문단별 상세 비교' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '한 문서로 보기' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/쉬운 글 단위 1,/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(bigText)
    // 재변환은 이 슬라이스(S3) 범위 밖이다(S4) — 이 화면에서 그런 버튼을 만들지 않는다.
    expect(screen.queryByRole('button', { name: /재변환/ })).not.toBeInTheDocument()
  })

  /**
   * CRITICAL 리뷰: 단위 안에서 Enter만 `handleSplit`을 타고, Shift+Enter·붙여넣기·드롭은
   * 브라우저가 `\n`이 이미 섞인 값을 그대로 `onChange`로 준다. 이 값을 지도 갱신 없이
   * 받으면 `units = value.split('\n')`만 늘어나고 `unitMap`은 그대로 남아 그 뒤 모든
   * 단위가 엉뚱한 지도 항목을 입는다. 세 경로 모두 같은 방식(그 자리에서 나뉘는 것으로
   * 보고 지도를 함께 다시 짬)으로 막히는지, 그리고 밀려난 뒤 단위는 원래 대응을 그대로
   * 유지하는지 재는 자리다.
   */
  describe('단위 안에 줄바꿈이 섞여 들어오는 경로(Shift+Enter·붙여넣기·드롭)', () => {
    function renderTwoUnits() {
      const map = segmentMap({
        source_unit_count: 2,
        units: [
          segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
          segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
        ],
      })
      renderDetailed(
        <ReviewEditor
          conversion={conversion({ easy_text: '첫줄\n둘째줄', segment_map: map })}
          source={sourceFailed()}
        />,
      )
      return screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    }

    it('Shift+Enter로 줄바꿈이 섞여도 지도 길이가 단위 수와 같고 뒤 단위는 원래 대응을 유지한다', async () => {
      const user = userEvent.setup()
      const unit1 = renderTwoUnits()
      unit1.focus()
      unit1.setSelectionRange(1, 1)

      await user.keyboard('{Shift>}{Enter}{/Shift}')

      expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫')
      expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('줄')
      // 밀려난 뒤 단위는 원래 대응(원본 2번째 문단, high)을 그대로 유지한다 — 지도
      // 갱신이 다른 단위까지 어긋나게 만들지 않았다는 뜻이다.
      expect(screen.getByLabelText('쉬운 글 단위 3, 원본 2번째 문단에 대응')).toHaveValue('둘째줄')
    })

    it('붙여넣기로 줄바꿈이 섞여도 지도 길이가 단위 수와 같고 뒤 단위는 원래 대응을 유지한다', async () => {
      const user = userEvent.setup()
      const unit1 = renderTwoUnits()
      unit1.focus()
      unit1.setSelectionRange(1, 1)

      await user.paste('X\nY')

      expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫X')
      expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('Y줄')
      expect(screen.getByLabelText('쉬운 글 단위 3, 원본 2번째 문단에 대응')).toHaveValue('둘째줄')
    })

    it('드롭으로 줄바꿈이 섞여도 지도 길이가 단위 수와 같고 뒤 단위는 원래 대응을 유지한다', () => {
      const unit1 = renderTwoUnits()

      // jsdom은 드롭의 기본 삽입 동작(브라우저가 드롭 지점에 텍스트를 끼워 넣고 input을
      // 흘려보내는 것)을 구현하지 않는다 — 실제 브라우저가 만들 최종 DOM 상태(이미 섞인
      // 값)를 change로 직접 재현해, onChange 처리 로직 자체를 검증한다.
      fireEvent.drop(unit1, { dataTransfer: { getData: () => 'X\nY' } })
      fireEvent.change(unit1, { target: { value: '첫X\nY줄' } })

      expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫X')
      expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('Y줄')
      expect(screen.getByLabelText('쉬운 글 단위 3, 원본 2번째 문단에 대응')).toHaveValue('둘째줄')
    })
  })
})

/** `reconvertUnit` 응답. 기본값은 HIGH 1:1 + 지문 불변 — 「바꾸기」 갈래를 탄다. */
function reconvertResponse(overrides: Partial<ReconvertUnitResponse> = {}): ReconvertUnitResponse {
  return {
    candidate_text: '다시 쓴 문장입니다.',
    source_unit_index: 0,
    easy_unit_indexes: [0],
    easy_text_fingerprint: 'a'.repeat(64),
    llm_calls_used: 1,
    remaining_call_budget: 19,
    ...overrides,
  }
}

describe('문단 재변환(계획 §4 결정 3, §6 S5)', () => {
  /** 원본·쉬운 글 각 1단위, high 1:1 대응. 재변환 테스트의 공통 뼈대다. */
  function renderOneUnit(easyText = '첫 문장') {
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    return renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: easyText, segment_map: map })}
        source={sourceReady('원본 문단')}
      />,
    )
  }

  it('다시 변환 버튼은 현재 매핑의 easy_unit_indexes와 본문 지문(SHA-256)으로 요청한다', async () => {
    const user = userEvent.setup()
    const expectedFingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_text_fingerprint: expectedFingerprint }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))

    await waitFor(() =>
      expect(reconvertUnit).toHaveBeenCalledWith('c1', 0, {
        easy_unit_indexes: [0],
        easy_text_fingerprint: expectedFingerprint,
      }),
    )
  })

  it('요청이 도는 동안 그 행은 진행 중 표시가 되고 다른 재변환은 걸리지 않는다(이중 제출 방지)', async () => {
    const user = userEvent.setup()
    let resolveReconvert: (value: ReconvertUnitResponse) => void = () => undefined
    vi.mocked(reconvertUnit).mockReturnValue(
      new Promise((resolve) => {
        resolveReconvert = resolve
      }),
    )
    renderOneUnit()

    const button = screen.getByLabelText('원본 1번째 문단 다시 변환')
    await user.click(button)
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).toBeDisabled()

    // 응답을 기다리는 동안 다시 눌러도 두 번째 요청을 걸지 않는다.
    await user.click(button)
    expect(reconvertUnit).toHaveBeenCalledTimes(1)

    resolveReconvert(reconvertResponse())
    await waitFor(() => expect(button).not.toBeDisabled())
  })

  it('HIGH 1:1 + 지문 불변 응답은 「바꾸기」 카드를 보여주고, 누르면 그 단위만 갈아 끼운다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_text_fingerprint: fingerprint }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const replaceButton = await screen.findByRole('button', { name: '바꾸기' })
    // 지문 불일치 응답이 아니다 — 「이 위치에 넣기」는 뜨지 않는다(계획 §6 S5 수용 기준).
    expect(screen.queryByRole('button', { name: '이 위치에 넣기' })).not.toBeInTheDocument()

    await user.click(replaceButton)

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue(
      '다시 쓴 문장입니다.',
    )
    // 단위 수(=지도 길이)는 그대로다 — 갈아 끼우기는 텍스트만 바꾼다.
    expect(screen.getAllByLabelText(/^쉬운 글 단위 \d+,/)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: '바꾸기' })).not.toBeInTheDocument()
  })

  it('1:N 응답(쉬운 글 단위 여럿)은 「이 위치에 넣기」만 보여준다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_unit_indexes: [0, 1], easy_text_fingerprint: fingerprint }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))

    await screen.findByRole('button', { name: '이 위치에 넣기' })
    expect(screen.queryByRole('button', { name: '바꾸기' })).not.toBeInTheDocument()
  })

  it('응답 도착 시점에 본문 지문이 바뀌어 있으면 HIGH 1:1이어도 「이 위치에 넣기」만 보여준다', async () => {
    const user = userEvent.setup()
    // 응답은 요청 시점(편집 전)의 지문을 그대로 되울린다 — 서버가 그 값으로 판정하지
    // 않는다(계획 §4 결정 3). 응답이 도착하기 전에 사용자가 결과를 고쳤다고 가정한다.
    const staleFingerprint = await computeEasyTextFingerprint('첫 문장')
    let resolveReconvert: (value: ReconvertUnitResponse) => void = () => undefined
    vi.mocked(reconvertUnit).mockReturnValue(
      new Promise((resolve) => {
        resolveReconvert = resolve
      }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    await user.type(screen.getByLabelText(/^쉬운 글 단위 1,/), '!')
    resolveReconvert(reconvertResponse({ easy_text_fingerprint: staleFingerprint }))

    await screen.findByRole('button', { name: '이 위치에 넣기' })
    expect(screen.queryByRole('button', { name: '바꾸기' })).not.toBeInTheDocument()
    // 자동으로는 어떤 경우에도 바뀌지 않는다 — 방금 입력한 「!」가 그대로 남아 있다.
    expect(screen.getByLabelText(/^쉬운 글 단위 1,/)).toHaveValue('첫 문장!')
  })

  it('「이 위치에 넣기」는 마지막으로 초점이 있던 단위의 캐럿에 끼워 넣고 단위 수는 그대로다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({
        candidate_text: '끼워넣기',
        easy_unit_indexes: [0, 1],
        easy_text_fingerprint: fingerprint,
      }),
    )
    renderOneUnit()

    const unit = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1) // '첫' 뒤

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const insertButton = await screen.findByRole('button', { name: '이 위치에 넣기' })
    await user.click(insertButton)

    expect(screen.getByLabelText(/^쉬운 글 단위 1,/)).toHaveValue('첫끼워넣기 문장')
    // 캐럿에 끼워 넣었을 뿐 단위를 나누지 않았다 — 단위 수(지도 길이)는 그대로다.
    expect(screen.getAllByLabelText(/^쉬운 글 단위 \d+,/)).toHaveLength(1)
  })

  it('캐럿을 모르면(초점이 간 적이 없으면) 새 단위로 붙는다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    // easy_unit_indexes를 빈 배열로 둔다 — 대응을 찾지 못했다는 뜻이라(HIGH 1:1 조건에
    // 못 미친다) 「이 위치에 넣기」 갈래를 탄다(계획 §4 결정 3).
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({
        candidate_text: '새 단위',
        easy_unit_indexes: [],
        easy_text_fingerprint: fingerprint,
      }),
    )
    renderOneUnit()

    // 결과 단위에 한 번도 초점을 준 적이 없는 채로 바로 재변환을 누른다.
    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const insertButton = await screen.findByRole('button', { name: '이 위치에 넣기' })
    await user.click(insertButton)

    // 단위가 하나 늘었다 — 지도 길이도 함께 늘어난다(SegmentedResultEditor의 정렬 규칙).
    expect(screen.getAllByLabelText(/^쉬운 글 단위 \d+,/)).toHaveLength(2)
    expect(screen.getByLabelText('쉬운 글 단위 2, 원본 1번째 문단에 대응')).toHaveValue('새 단위')
  })

  it('「바꾸기」 후보 텍스트에 개행이 섞여 있으면(1:N이 아니라 후보 본문 자체) 단위가 늘고 지도 길이도 함께 늘어난다(HIGH 리뷰 1)', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({
        candidate_text: '다시 쓴 문장 1\n다시 쓴 문장 2',
        easy_text_fingerprint: fingerprint,
      }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const replaceButton = await screen.findByRole('button', { name: '바꾸기' })
    await user.click(replaceButton)

    // 후보 텍스트의 개행 때문에 단위가 하나 늘었다 — 지도 길이(`unitMap.length`)도
    // `draft.split('\n').length`와 같게 함께 늘어난다.
    expect(screen.getAllByLabelText(/^쉬운 글 단위 \d+,/)).toHaveLength(2)
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue(
      '다시 쓴 문장 1',
    )
    // 새로 생긴 두 번째 조각은 원래 단위의 대응을 물려받지 않는다 — 「대응 확인 불가」로
    // 안전하게 둔다(SegmentedResultEditor.handleUnitTextChange와 같은 규칙).
    expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('다시 쓴 문장 2')
  })

  it('「이 위치에 넣기」 후보 텍스트에 개행이 섞여 있으면 캐럿 자리에서 단위가 나뉘고 지도 길이도 함께 늘어난다(HIGH 리뷰 1)', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({
        candidate_text: '끼워 1\n끼워 2',
        easy_unit_indexes: [0, 1],
        easy_text_fingerprint: fingerprint,
      }),
    )
    renderOneUnit()

    const unit = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1) // '첫' 뒤

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const insertButton = await screen.findByRole('button', { name: '이 위치에 넣기' })
    await user.click(insertButton)

    // 캐럿 자리에서 갈라져 단위가 하나 늘었다.
    expect(screen.getAllByLabelText(/^쉬운 글 단위 \d+,/)).toHaveLength(2)
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫끼워 1')
    expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('끼워 2 문장')
  })

  it('재변환 후보 카드가 뜨면 초점이 카드로 옮겨간다(MEDIUM 리뷰 3)', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_text_fingerprint: fingerprint }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))

    const card = await screen.findByRole('region', { name: '원본 1번째 문단 재변환 후보' })
    await waitFor(() => expect(card).toHaveFocus())
    // 카드가 도착했다는 사실 자체를 낭독하는 자리(role="status")도 함께 있다.
    expect(screen.getByText(/재변환 후보가 도착했습니다/)).toBeInTheDocument()
  })

  it('재변환 후보 카드를 닫으면 초점이 그 카드를 연 「다시 변환」 버튼으로 돌아간다(MEDIUM 리뷰 3)', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_text_fingerprint: fingerprint }),
    )
    renderOneUnit()

    const triggerButton = screen.getByLabelText('원본 1번째 문단 다시 변환')
    await user.click(triggerButton)

    await screen.findByRole('region', { name: '원본 1번째 문단 재변환 후보' })
    await user.click(screen.getByLabelText('재변환 후보 닫기'))

    expect(
      screen.queryByRole('region', { name: '원본 1번째 문단 재변환 후보' }),
    ).not.toBeInTheDocument()
    expect(triggerButton).toHaveFocus()
  })

  it('「바꾸기」를 연속으로 두 번 눌러도 후보를 두 번 끼워 넣지 않는다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_text_fingerprint: fingerprint }),
    )
    renderOneUnit()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const replaceButton = await screen.findByRole('button', { name: '바꾸기' })
    // 두 번 클릭(더블클릭에 준함) — 첫 클릭 뒤 카드가 사라지므로 두 번째는 표적이 없다.
    fireEvent.click(replaceButton)
    fireEvent.click(replaceButton)

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue(
      '다시 쓴 문장입니다.',
    )
  })

  it('429는 예산을 0으로 보여주고 모든 재변환 버튼을 잠근다', async () => {
    const user = userEvent.setup()
    vi.mocked(reconvertUnit).mockRejectedValue(
      new ApiError(429, '재변환 호출 예산을 모두 사용했습니다', null, 0),
    )
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 하나\n원본 둘')}
      />,
    )

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))

    // 어느 문단에서 실패했는지 서수를 앞에 붙인다(LOW 리뷰 4).
    expect(await screen.findByText('1번째 문단: 재변환 예산을 모두 썼습니다.')).toBeInTheDocument()
    expect(screen.getByText('남은 재변환 0회')).toBeInTheDocument()
    expect(screen.getByLabelText('원본 1번째 문단 다시 변환')).toBeDisabled()
    expect(screen.getByLabelText('원본 2번째 문단 다시 변환')).toBeDisabled()
  })

  it('429 응답의 남은 예산이 1이어도(예약분 2 중 하나만 남음) 소진으로 보고 모든 버튼을 잠근다(MEDIUM-HIGH 리뷰)', async () => {
    const user = userEvent.setup()
    vi.mocked(reconvertUnit).mockRejectedValue(
      new ApiError(429, '재변환 호출 예산을 모두 사용했습니다', null, 1),
    )
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 하나\n원본 둘')}
      />,
    )

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))

    expect(await screen.findByText('1번째 문단: 재변환 예산을 모두 썼습니다.')).toBeInTheDocument()
    expect(screen.getByText('남은 재변환 1회')).toBeInTheDocument()
    expect(screen.getByLabelText('원본 1번째 문단 다시 변환')).toBeDisabled()
    expect(screen.getByLabelText('원본 2번째 문단 다시 변환')).toBeDisabled()
  })

  it('503은 재시도 카운트다운을 보여준다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      vi.mocked(reconvertUnit).mockRejectedValue(
        new ApiError(503, '동시 재변환 한도에 도달했습니다', 1, null),
      )
      renderOneUnit()

      fireEvent.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
      await vi.waitFor(() =>
        expect(
          screen.getByText('1번째 문단: 잠시 후 다시 시도해 주세요. (1초)'),
        ).toBeInTheDocument(),
      )

      await act(async () => {
        vi.advanceTimersByTime(1000)
      })

      expect(
        screen.queryByText('1번째 문단: 잠시 후 다시 시도해 주세요. (1초)'),
      ).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('저장이 도는 동안에는 재변환 버튼도 잠긴다', async () => {
    const user = userEvent.setup()
    let resolveSave: (value: ConversionResponse) => void = () => undefined
    vi.mocked(saveReview).mockReturnValue(
      new Promise((resolve) => {
        resolveSave = resolve
      }),
    )
    renderOneUnit()

    await user.type(screen.getByLabelText(/^쉬운 글 단위 1,/), '!')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(screen.getByLabelText('원본 1번째 문단 다시 변환')).toBeDisabled()

    resolveSave(conversion({ edited_text: '첫 문장!', reviewed_at: '2026-08-07T02:00:00Z' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '검수 내용 저장' })).toBeEnabled(),
    )
  })

  it('성공 응답 뒤 남은 재변환 횟수를 패널 머리 가까이 보여준다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({ easy_text_fingerprint: fingerprint, remaining_call_budget: 18 }),
    )
    renderOneUnit()

    expect(screen.queryByText(/^남은 재변환/)).not.toBeInTheDocument()
    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))

    expect(await screen.findByText('남은 재변환 18회')).toBeInTheDocument()
  })
})

describe('결과 단위 재시도·되돌리기(Part C-2/C-3)', () => {
  /** 원본·쉬운 글 각 2단위, 둘 다 high 1:1 대응. */
  function renderTwoUnits() {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    return renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 하나\n원본 둘')}
      />,
    )
  }

  it('재시도는 그 단위의 단일 원본 색인으로 재변환을 걸고, 성공하면 그 단위 아래 바꾸기 카드가 뜨며 채택은 그 단위만 바꾼다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장\n둘째 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({
        source_unit_index: 1,
        easy_unit_indexes: [1],
        easy_text_fingerprint: fingerprint,
        candidate_text: '다시 쓴 둘째 문장',
      }),
    )
    renderTwoUnits()

    await user.click(screen.getByLabelText('쉬운 글 단위 2 재시도'))

    await waitFor(() =>
      expect(reconvertUnit).toHaveBeenCalledWith(
        'c1',
        1,
        expect.objectContaining({ easy_unit_indexes: [1] }),
      ),
    )

    const replaceButton = await screen.findByRole('button', { name: '바꾸기' })
    await user.click(replaceButton)

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫 문장')
    expect(screen.getByLabelText('쉬운 글 단위 2, 원본 2번째 문단에 대응')).toHaveValue(
      '다시 쓴 둘째 문장',
    )
  })

  it('대응하는 원본 색인이 없으면 재시도가 비활성이고 사유를 알린다', () => {
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [], confidence: 'low' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장', segment_map: map })}
        source={sourceReady('원본')}
      />,
    )

    const retryButton = screen.getByLabelText('쉬운 글 단위 1 재시도')
    expect(retryButton).toBeDisabled()
    expect(retryButton).toHaveAttribute(
      'title',
      '대응하는 원본 문단을 찾지 못했습니다. 원본 패널에서 다시 변환해 주세요.',
    )
  })

  it('원본 색인이 둘 이상이면 재시도가 비활성이고 사유를 알린다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0, 1], confidence: 'low' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장', segment_map: map })}
        source={sourceReady('원본 하나\n원본 둘')}
      />,
    )

    const retryButton = screen.getByLabelText('쉬운 글 단위 1 재시도')
    expect(retryButton).toBeDisabled()
    expect(retryButton).toHaveAttribute(
      'title',
      '원본 문단 여러 개에 대응합니다. 원본 패널에서 문단을 골라 다시 변환해 주세요.',
    )
  })

  it('낮은 신뢰도라도 원본 색인이 하나면 재시도가 활성이다', () => {
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'low' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장', segment_map: map })}
        source={sourceReady('원본')}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 단위 1 재시도')).toBeEnabled()
  })

  it('한 재시도가 도는 동안 다른 행의 재시도 버튼은 잠기고, 도는 행만 진행 중 표시다', async () => {
    const user = userEvent.setup()
    let resolveReconvert: (value: ReconvertUnitResponse) => void = () => undefined
    vi.mocked(reconvertUnit).mockReturnValue(
      new Promise((resolve) => {
        resolveReconvert = resolve
      }),
    )
    renderTwoUnits()

    const retry1 = screen.getByLabelText('쉬운 글 단위 1 재시도')
    const retry2 = screen.getByLabelText('쉬운 글 단위 2 재시도')
    await user.click(retry1)

    expect(retry1).toHaveAttribute('aria-busy', 'true')
    expect(retry1).toBeDisabled()
    expect(retry2).toBeDisabled()
    expect(retry2).toHaveAttribute('title', '다른 재변환이 진행 중입니다.')

    resolveReconvert(reconvertResponse())
    await waitFor(() => expect(retry1).not.toBeDisabled())
  })

  it('되돌리기는 처음엔 비활성이고, 고치면 활성화되며 누르면 원래 값과 저장 상태로 돌아간다', async () => {
    const user = userEvent.setup()
    renderTwoUnits()

    const revertButton = screen.getByLabelText('쉬운 글 단위 1 되돌리기')
    expect(revertButton).toBeDisabled()
    expect(screen.queryByText('저장 안 됨')).not.toBeInTheDocument()

    const unit1 = screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')
    fireEvent.change(unit1, { target: { value: '고친 문장' } })

    expect(revertButton).toBeEnabled()
    expect(screen.getByText('저장 안 됨')).toBeInTheDocument()

    await user.click(revertButton)

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫 문장')
    expect(screen.queryByText('저장 안 됨')).not.toBeInTheDocument()
  })

  it('바꾸기로 채택한 뒤에도 되돌리기는 채택 전 텍스트로 돌아간다', async () => {
    const user = userEvent.setup()
    const fingerprint = await computeEasyTextFingerprint('첫 문장\n둘째 문장')
    vi.mocked(reconvertUnit).mockResolvedValue(
      reconvertResponse({
        source_unit_index: 0,
        easy_unit_indexes: [0],
        easy_text_fingerprint: fingerprint,
        candidate_text: '다시 쓴 문장',
      }),
    )
    renderTwoUnits()

    await user.click(screen.getByLabelText('원본 1번째 문단 다시 변환'))
    const replaceButton = await screen.findByRole('button', { name: '바꾸기' })
    await user.click(replaceButton)
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue(
      '다시 쓴 문장',
    )

    await user.click(screen.getByLabelText('쉬운 글 단위 1 되돌리기'))

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫 문장')
  })

  it('Enter로 나뉜 두 단위 모두 되돌리기가 비활성이다', () => {
    const map = segmentMap({
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceReady('원본')}
      />,
    )
    const unit = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1)
    fireEvent.keyDown(unit, { key: 'Enter' })

    expect(screen.getByLabelText('쉬운 글 단위 1 되돌리기')).toBeDisabled()
    expect(screen.getByLabelText('쉬운 글 단위 2 되돌리기')).toBeDisabled()
  })

  it('저장 응답의 edited_text가 지금 draft와 같으면 되돌리기가 다시 비활성이다(새 기준선)', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockImplementation((_id, text) =>
      Promise.resolve(conversion({ edited_text: text })),
    )
    renderTwoUnits()

    const unit1 = screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')
    fireEvent.change(unit1, { target: { value: '고친 문장' } })
    expect(screen.getByLabelText('쉬운 글 단위 1 되돌리기')).toBeEnabled()

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))
    await waitFor(() => expect(saveReview).toHaveBeenCalled())

    expect(screen.getByLabelText('쉬운 글 단위 1 되돌리기')).toBeDisabled()
  })

  it('baseline이 빈 문자열인 단위를 되돌리면(로드 시 공백이던 줄에 나중에 글을 쓴 경우) 행이 사라지지 않고 초점을 유지한다(MEDIUM 리뷰)', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(conversion({ edited_text: '첫째\n\n둘째' }))
    const map = segmentMap({
      source_unit_count: 3,
      units: [0, 1, 2].map((index) =>
        segmentMapUnit({
          easy_unit_index: index,
          source_unit_indexes: [index],
          confidence: 'high',
        }),
      ),
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫째\n\n둘째', segment_map: map })}
        source={sourceReady('가\n\n나')}
      />,
    )

    // 단일 글상자 모드에서 처음엔 공백이던 둘째 줄에 글을 쓴다 — 이 시점의
    // 대응표(baseline 포함)는 구조가 바뀌지 않았으므로 그대로 남는다.
    const wholeTextarea = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    fireEvent.change(wholeTextarea, { target: { value: '첫째\n둘째 줄\n둘째' } })

    await user.click(screen.getByRole('button', { name: '문단별 상세 비교' }))

    const revertButton = screen.getByLabelText('쉬운 글 단위 2 되돌리기')
    expect(revertButton).toBeEnabled()
    await user.click(revertButton)

    const unit2 = screen.getByLabelText('쉬운 글 단위 2, 원본 2번째 문단에 대응')
    expect(unit2).toBeInTheDocument()
    expect(unit2).toHaveFocus()
    expect(unit2).toHaveValue('')

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))
    expect(saveReview).toHaveBeenCalledWith('c1', '첫째\n\n둘째', 1)
  })

  it('같은 원본 색인을 공유하는 여러 쉬운 글 단위 중 재시도를 건 행만 진행 중 표시다(MEDIUM 리뷰)', async () => {
    const user = userEvent.setup()
    let resolveReconvert: (value: ReconvertUnitResponse) => void = () => undefined
    vi.mocked(reconvertUnit).mockReturnValue(
      new Promise((resolve) => {
        resolveReconvert = resolve
      }),
    )
    const map = segmentMap({
      source_unit_count: 1,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [0], confidence: 'high' }),
      ],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본')}
      />,
    )

    const retry1 = screen.getByLabelText('쉬운 글 단위 1 재시도')
    const retry2 = screen.getByLabelText('쉬운 글 단위 2 재시도')
    await user.click(retry2)

    expect(retry2).toHaveAttribute('aria-busy', 'true')
    expect(retry2).toBeDisabled()
    expect(retry1).not.toHaveAttribute('aria-busy')
    expect(retry1).toBeDisabled()
    expect(retry1).toHaveAttribute('title', '다른 재변환이 진행 중입니다.')

    resolveReconvert(reconvertResponse())
    await waitFor(() => expect(retry2).not.toBeDisabled())
  })

  it('재시도로 건 재변환이 실패하면 오류 문구가 원본 서수가 아니라 그 쉬운 글 단위를 가리킨다(LOW 리뷰)', async () => {
    const user = userEvent.setup()
    vi.mocked(reconvertUnit).mockRejectedValue(new ApiError(502, '변환 서버 오류'))
    renderTwoUnits()

    await user.click(screen.getByLabelText('쉬운 글 단위 2 재시도'))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(/^쉬운 글 단위 2:/)
  })

  it('재시도로 건 503은 그 쉬운 글 단위를 가리키는 카운트다운을 보여준다(LOW 리뷰)', async () => {
    vi.mocked(reconvertUnit).mockRejectedValue(
      new ApiError(503, '동시 재변환 한도에 도달했습니다', 1, null),
    )
    renderTwoUnits()

    fireEvent.click(screen.getByLabelText('쉬운 글 단위 2 재시도'))

    expect(
      await screen.findByText('쉬운 글 단위 2: 잠시 후 다시 시도해 주세요. (1초)'),
    ).toBeInTheDocument()
  })
})

describe('이미 통과한 문단 경고(계획 §11, segment_map.compliant_source_units)', () => {
  function renderTwoUnits(compliantSourceUnits: number[]) {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
      compliant_source_units: compliantSourceUnits,
    })
    return renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 문단 하나\n원본 문단 둘')}
      />,
    )
  }

  it('목록에 있는 원본 단위 행에만 배지가 붙고, 그 행의 다시 변환 버튼은 여전히 활성이다', () => {
    renderTwoUnits([0])

    expect(screen.getByLabelText('원본 1번째 문단').closest('[role="listitem"]')).toHaveTextContent(
      '이미 쉬운 글 규칙을 통과한 문단',
    )
    expect(
      screen.getByLabelText('원본 2번째 문단').closest('[role="listitem"]'),
    ).not.toHaveTextContent('이미 쉬운 글 규칙을 통과한 문단')

    const reconvertButton = screen.getByLabelText('원본 1번째 문단 다시 변환')
    expect(reconvertButton).toBeEnabled()

    const describedBy = reconvertButton.getAttribute('aria-describedby')
    expect(describedBy).not.toBeNull()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(
      '이 문단은 이미 쉬운 글 규칙을 통과합니다. 다시 쓰면 나빠질 수 있습니다.',
    )

    // 다른 비활성 사유가 없으면 title도 같은 경고 문구를 낸다.
    expect(reconvertButton).toHaveAttribute(
      'title',
      '이 문단은 이미 쉬운 글 규칙을 통과합니다. 다시 쓰면 나빠질 수 있습니다.',
    )

    // 통과하지 못한 행에는 설명 참조도, 경고 title도 없다.
    const otherButton = screen.getByLabelText('원본 2번째 문단 다시 변환')
    expect(otherButton).not.toHaveAttribute('aria-describedby')
    expect(otherButton).not.toHaveAttribute('title')
  })

  it('목록이 비어 있으면 배지가 하나도 없다', () => {
    renderTwoUnits([])

    expect(screen.queryByText('이미 쉬운 글 규칙을 통과한 문단')).not.toBeInTheDocument()
    expect(screen.getByLabelText('원본 1번째 문단 다시 변환')).not.toHaveAttribute(
      'aria-describedby',
    )
  })
})

describe('구조 배지(P0-4 S8, 계획 §1.5, segment_map.source_unit_kinds)', () => {
  // 「본문 → run 3줄 → 본문」 다섯 줄. run이 중간에 있어 「첫 행에만 배지」·「양 옆 body는
  // 그룹 밖」을 한 번에 확인할 수 있다. 줄 내용은 배지·노트 문구(「표 칸」·「목록」)와
  // 우연히 겹치지 않는 중립적인 자리표시자로 둔다 — 겹치면 `toHaveTextContent` 단언이
  // 배지가 아니라 원문 자체와 매치해 거짓양성이 난다.
  const sourceText = '본문 A\nline1\nline2\nline3\n본문 B'

  function renderWithKinds(
    kinds: Array<'body' | 'table_cell' | 'list_item'>,
    overrides: Partial<ReturnType<typeof segmentMap>> = {},
  ) {
    const map = segmentMap({
      source_unit_count: kinds.length,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [4], confidence: 'high' }),
      ],
      source_unit_kinds: kinds,
      ...overrides,
    })
    return renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '변환 A\n변환 B', segment_map: map })}
        source={sourceReady(sourceText)}
      />,
    )
  }

  it('run 첫 행에만 배지가 붙고, 그룹 aria-label의 개수가 run 길이와 같다(C2)', () => {
    renderWithKinds(['body', 'table_cell', 'table_cell', 'table_cell', 'body'])

    // 배지 텍스트는 run 하나당 한 번뿐이다 — 그룹의 aria-label(「표 칸 3개」)과
    // 겹치지 않는다(속성이지 렌더된 텍스트가 아니다).
    expect(screen.getAllByText('표 칸')).toHaveLength(1)

    const group = screen.getByRole('group', { name: '표 칸 3개' })
    const rows = within(group).getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('표 칸')
    expect(rows[1]).not.toHaveTextContent('표 칸')
    expect(rows[2]).not.toHaveTextContent('표 칸')

    // run 밖의 본문 행은 그룹에 들어가지 않는다.
    expect(screen.getByLabelText('원본 1번째 문단').closest('[role="listitem"]')).not.toBe(
      within(group).queryByLabelText('원본 1번째 문단'),
    )
  })

  it('목록 run도 같은 규칙을 따른다', () => {
    renderWithKinds(['body', 'list_item', 'list_item', 'list_item', 'body'])

    expect(screen.getAllByText('목록')).toHaveLength(1)
    const group = screen.getByRole('group', { name: '목록 항목 3개' })
    expect(within(group).getAllByRole('listitem')).toHaveLength(3)
  })

  it('전부 본문이면 배지도 그룹도 없다(C3)', () => {
    renderWithKinds(['body', 'body', 'body', 'body', 'body'])

    expect(screen.queryByText('표 칸')).not.toBeInTheDocument()
    expect(screen.queryByText('목록')).not.toBeInTheDocument()
    // 결과 패널(`SegmentedResultEditor`)도 자기 role="group" 컨테이너를 하나 낸다 —
    // 그것과 헷갈리지 않도록 구조 그룹의 aria-label(「표 칸 N개」·「목록 항목 N개」)로만
    // 좁혀서 찾는다.
    expect(screen.queryByRole('group', { name: /표 칸|목록 항목/ })).not.toBeInTheDocument()
  })

  it('옛 문서(source_unit_kinds 미지정 → 기본값 전부 body)도 낱개 목록 그대로다', () => {
    const map = segmentMap({ source_unit_count: 5 })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '변환 A', segment_map: map })}
        source={sourceReady(sourceText)}
      />,
    )

    expect(screen.queryByRole('group', { name: /표 칸|목록 항목/ })).not.toBeInTheDocument()
    expect(screen.queryByText('표 칸')).not.toBeInTheDocument()
  })

  it('셀 행의 다시 변환 버튼은 활성이고, aria-describedby가 표 칸 노트를 가리킨다', () => {
    renderWithKinds(['body', 'table_cell', 'table_cell', 'table_cell', 'body'])

    const button = screen.getByLabelText('원본 2번째 문단 다시 변환')
    expect(button).toBeEnabled()

    const describedBy = button.getAttribute('aria-describedby')
    expect(describedBy).not.toBeNull()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(
      '이 문단은 표의 칸입니다. 칸 하나로 유지됩니다.',
    )
  })

  it('목록 행의 다시 변환 버튼은 목록 노트를 가리킨다', () => {
    renderWithKinds(['body', 'list_item', 'list_item', 'list_item', 'body'])

    const button = screen.getByLabelText('원본 3번째 문단 다시 변환')
    const describedBy = button.getAttribute('aria-describedby')
    expect(describedBy).not.toBeNull()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(
      '이 문단은 목록 항목입니다. 항목 하나로 유지됩니다.',
    )
  })

  it('이미 통과 배지(S7)와 구조 배지(S8)가 같은 행에 함께 붙고, 두 노트를 모두 참조한다', () => {
    renderWithKinds(['body', 'table_cell', 'table_cell', 'table_cell', 'body'], {
      compliant_source_units: [1],
    })

    const row = screen.getByLabelText('원본 2번째 문단').closest('[role="listitem"]')
    expect(row).toHaveTextContent('표 칸')
    expect(row).toHaveTextContent('이미 쉬운 글 규칙을 통과한 문단')

    const button = screen.getByLabelText('원본 2번째 문단 다시 변환')
    expect(button).toBeEnabled()
    const ids = (button.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    expect(ids).toHaveLength(2)
    const texts = ids.map((id) => document.getElementById(id)?.textContent)
    expect(texts).toContain(
      '이 문단은 이미 쉬운 글 규칙을 통과합니다. 다시 쓰면 나빠질 수 있습니다.',
    )
    expect(texts).toContain('이 문단은 표의 칸입니다. 칸 하나로 유지됩니다.')
  })
})

describe('빈 줄(공백뿐) 단위 숨김(Part B)', () => {
  /** 3단위(문단 구분 빈 줄 포함) 공통 뼈대. 원본·결과 모두 가운데 줄이 공백뿐이다. */
  function renderWithBlankMiddleUnit() {
    const map = segmentMap({
      source_unit_count: 3,
      units: [0, 1, 2].map((index) =>
        segmentMapUnit({
          easy_unit_index: index,
          source_unit_indexes: [index],
          confidence: 'high',
        }),
      ),
    })
    return renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫째\n\n둘째', segment_map: map })}
        source={sourceReady('가\n\n나')}
      />,
    )
  }

  it('결과 패널은 공백뿐인 단위를 감추지만 나머지 색인은 그대로 둔다', () => {
    renderWithBlankMiddleUnit()

    expect(screen.getAllByLabelText(/^쉬운 글 단위 \d+,/)).toHaveLength(2)
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫째')
    expect(screen.getByLabelText('쉬운 글 단위 3, 원본 3번째 문단에 대응')).toHaveValue('둘째')
    expect(screen.queryByLabelText(/^쉬운 글 단위 2,/)).not.toBeInTheDocument()
  })

  it('원본 패널은 공백뿐인 문단을 감추지만 나머지 색인은 그대로 둔다', () => {
    renderWithBlankMiddleUnit()

    expect(screen.getByLabelText('원본 3번째 문단')).toHaveValue('나')
    expect(screen.queryByLabelText('원본 2번째 문단')).not.toBeInTheDocument()
  })

  it('저장은 감춰진 빈 줄도 그대로 포함해 draft를 보낸다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(conversion({ edited_text: '첫째\n\n둘째' }))
    renderWithBlankMiddleUnit()

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(saveReview).toHaveBeenCalledWith('c1', '첫째\n\n둘째', 1)
  })

  it('단위 끝에서 Enter를 누르면 새로 생긴 빈 단위도 그려 초점을 맞추고, 값을 채우지 않고 벗어나면 다시 감춘다', async () => {
    const map = segmentMap({
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceReady('원본')}
      />,
    )
    const unit = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(unit.value.length, unit.value.length)
    fireEvent.keyDown(unit, { key: 'Enter' })

    const newUnit = await screen.findByLabelText(/^쉬운 글 단위 2,/)
    expect(newUnit).toHaveFocus()
    expect(newUnit).toHaveValue('')

    fireEvent.blur(newUnit)
    expect(screen.queryByLabelText(/^쉬운 글 단위 2,/)).not.toBeInTheDocument()
  })

  it('ArrowDown은 빈 단위를 건너뛰어 다음으로 보이는 단위에 초점을 맞춘다', () => {
    renderWithBlankMiddleUnit()

    const unit1 = screen.getByLabelText(/^쉬운 글 단위 1,/) as HTMLTextAreaElement
    unit1.focus()
    unit1.setSelectionRange(unit1.value.length, unit1.value.length)
    fireEvent.keyDown(unit1, { key: 'ArrowDown' })

    expect(screen.getByLabelText(/^쉬운 글 단위 3,/)).toHaveFocus()
  })
})

describe('저장 중 경합 방지(MEDIUM 리뷰)', () => {
  it('저장·내려받기가 도는 동안 단위 textarea를 잠근다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockReturnValue(new Promise<ConversionResponse>(() => undefined))
    const map = segmentMap({
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    renderDetailed(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(screen.getByLabelText(/^쉬운 글 단위 1,/)).toBeDisabled()
  })

  it('저장이 도는 동안 이어서 고치면 응답이 그 사이의 수정을 덮어쓰지 않는다', async () => {
    const user = userEvent.setup()
    let resolveSave: (value: ConversionResponse) => void = () => undefined
    vi.mocked(saveReview).mockReturnValue(
      new Promise<ConversionResponse>((resolve) => {
        resolveSave = resolve
      }),
    )
    renderDetailed(
      <ReviewEditor conversion={conversion({ easy_text: '초안' })} source={sourceFailed()} />,
    )

    const textarea = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)') as HTMLTextAreaElement
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    // 저장 응답이 오기 전에 이어서 고친다. 지금 이 입력칸은 busy 동안 disabled지만,
    // 여기서는 응답 처리 로직 자체(persistDraft의 낡은 응답 판정)를 검증하려고 값
    // 변경을 직접 흘려보낸다.
    fireEvent.change(textarea, { target: { value: '이어서 고친 글' } })

    resolveSave(conversion({ edited_text: '초안', reviewed_at: '2026-08-07T02:00:00Z' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '검수 내용 저장' })).not.toBeDisabled(),
    )

    // 응답(옛 초안 기준)이 그 사이에 고친 글을 덮어쓰지 않았다.
    expect(textarea).toHaveValue('이어서 고친 글')
  })
})

/**
 * 원문 패널(읽기 전용)에서도 사전 조회는 되고 적용 버튼은 없다(계획 §3.5, HIGH 리뷰 1).
 * `TermLookupPopover`는 결과 패널뿐 아니라 원문 패널 컨테이너에도 별도 인스턴스로 붙는다.
 */
describe('원문 패널의 사전 조회', () => {
  it('원문에서 선택하면 팝업에 후보가 뜨지만 바꾸기 버튼은 없다', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(lookupTerm).mockResolvedValue({
        query: '구비서류',
        candidates: [
          {
            term: '구비서류',
            easy_term: '준비할 서류',
            strategy: 'substitute',
            risk: 'none',
            definition: '신청할 때 미리 갖춰야 하는 서류',
            caution: null,
            tags: [],
            examples: [],
            match_kind: 'exact',
            applicable: true,
          },
        ],
        dictionary: { name: '쉬운 말 사전', license: 'CC-BY', schema_version: '1.0.0' },
      })
      render(
        <ReviewEditor
          conversion={conversion({ easy_text: '결과 글', edited_text: null })}
          source={sourceReady('구비서류를 준비하세요')}
        />,
      )
      const sourceTextarea = screen.getByLabelText('원본 (읽기 전용)') as HTMLTextAreaElement
      sourceTextarea.focus()
      sourceTextarea.setSelectionRange(0, 4) // "구비서류"
      fireEvent.mouseUp(sourceTextarea)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(250)
      })

      expect(screen.getByRole('dialog', { name: '쉬운 말 후보' })).toBeInTheDocument()
      expect(screen.getByText('준비할 서류')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '바꾸기' })).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('R1 검수 지원 패널', () => {
  const reviewCapabilities = {
    review_support: true,
    action_guide: false,
    table_relations: false,
    review_history: false,
    explanations: false,
    illustrations: false,
    focused_review: false,
  }

  it('capability가 없는 옛 응답에는 검수 패널을 노출하지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문')} />)

    expect(screen.queryByRole('button', { name: /검수할 내용/ })).not.toBeInTheDocument()
    expect(analyzeReviewSupport).not.toHaveBeenCalled()
  })

  it('처음 펼칠 때 현재 revision을 분석하고 누락 의심과 관계 5항목을 구분해 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    render(
      <ReviewEditor
        conversion={conversion({ content_revision: 1, review_capabilities: reviewCapabilities })}
        source={sourceReady('원문 1\n원문 2\n원문 3\n원문 4\n원문 5')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))

    expect(await screen.findByText('대상 확인')).toBeInTheDocument()
    expect(screen.getByText('모두·하나 확인')).toBeInTheDocument()
    expect(screen.getByText('예외 확인')).toBeInTheDocument()
    expect(screen.getByText('기한·행동 확인')).toBeInTheDocument()
    expect(screen.getByText('금액·적용 대상 확인')).toBeInTheDocument()
    expect(screen.getByText(/모든 의미가 보존됐다는 뜻은 아닙니다/)).toBeInTheDocument()
    expect(analyzeReviewSupport).toHaveBeenCalledWith('c1', {
      expected_content_revision: 1,
    })
  })

  it('분석 중 본문 충돌이면 현재 편집을 보호하고 최신 revision으로 다시 분석한다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport)
      .mockRejectedValueOnce(new ApiError(409, '본문 revision이 바뀌었습니다'))
      .mockResolvedValueOnce(
        reviewSupportResponse({
          assessment: {
            ...reviewSupportResponse().assessment!,
            content_revision: 2,
          },
        }),
      )
    vi.mocked(getConversion).mockResolvedValue(
      conversion({
        edited_text: '최신 저장 내용',
        content_revision: 2,
        review_capabilities: reviewCapabilities,
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({ content_revision: 1, review_capabilities: reviewCapabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('최신 내용을 불러온 뒤')
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '최신 내용 불러오기' }))
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue('최신 저장 내용')
    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    await screen.findAllByText('누락 의심')
    expect(analyzeReviewSupport).toHaveBeenLastCalledWith('c1', {
      expected_content_revision: 2,
    })
  })

  it('segment map이 없으면 원문 보기로 전체 원문에 이동하고 돌아갈 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: reviewCapabilities, segment_map: null })}
        source={sourceReady('신청은 3월 2일까지입니다.\n조건입니다.')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    await screen.findAllByText('누락 의심')
    expect(
      screen.getByText('쉬운 글의 정확한 위치 대신 원문 전체에서 비교합니다.'),
    ).toBeInTheDocument()

    const sourceButton = screen.getAllByRole('button', { name: '원문 보기' })[0]
    expect(sourceButton).toBeDefined()
    await user.click(sourceButton!)
    await waitFor(() => expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveFocus())

    await user.click(screen.getByRole('button', { name: '검수 항목으로 돌아가기' }))
    expect(sourceButton).toHaveFocus()
  })

  it('200단위를 넘는 문서에서도 체크리스트를 유지하고 전체 원문 fallback을 쓴다', async () => {
    const user = userEvent.setup()
    const unitCount = 201
    const text = Array.from({ length: unitCount }, (_, index) => `원문 ${index + 1}`).join('\n')
    const map = segmentMap({
      source_unit_count: unitCount,
      units: Array.from({ length: unitCount }, (_, index) =>
        segmentMapUnit({
          easy_unit_index: index,
          source_unit_indexes: [index],
          confidence: 'high',
        }),
      ),
    })
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    render(
      <ReviewEditor
        conversion={conversion({
          easy_text: text,
          segment_map: map,
          review_capabilities: reviewCapabilities,
        })}
        source={sourceReady(text)}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    expect(await screen.findByText('조건 관계 확인')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '문단별 상세 비교' })).not.toBeInTheDocument()
    expect(
      screen.getByText('쉬운 글의 정확한 위치 대신 원문 전체에서 비교합니다.'),
    ).toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[0]!)
    await waitFor(() => expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveFocus())
  })

  it('해당 없음은 사유를 요구하고 assessment·본문·검수 revision으로 저장한다', async () => {
    const user = userEvent.setup()
    const current = reviewSupportResponse()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(current)
    vi.mocked(updateReviewSupportItem).mockResolvedValue(current)
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: reviewCapabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    await screen.findAllByText('누락 의심')
    await user.click(screen.getAllByRole('button', { name: '해당 없음' })[0]!)
    await user.click(screen.getByRole('button', { name: '사유와 함께 저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이유를 적어 주세요')
    expect(updateReviewSupportItem).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('해당하지 않는 이유'), '이 문서에는 날짜가 없습니다.')
    await user.click(screen.getByRole('button', { name: '사유와 함께 저장' }))
    expect(updateReviewSupportItem).toHaveBeenCalledWith('c1', 'missing-1', {
      assessment_id: 'assessment-1',
      expected_content_revision: 1,
      expected_review_revision: 1,
      state: 'not_applicable',
      reason: '이 문서에는 날짜가 없습니다.',
    })
  })

  it('해당 없음 사유는 서버와 같은 기준으로 이모지 500자를 허용한다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: reviewCapabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    await screen.findAllByText('누락 의심')
    await user.click(screen.getAllByRole('button', { name: '해당 없음' })[0]!)
    const reason = screen.getByLabelText('해당하지 않는 이유')
    fireEvent.change(reason, { target: { value: '😀'.repeat(501) } })

    expect(Array.from((reason as HTMLTextAreaElement).value)).toHaveLength(500)
  })

  it('항목 저장 충돌 뒤 최신 검수 조회까지 실패하면 성공 안내를 표시하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    vi.mocked(updateReviewSupportItem).mockRejectedValue(
      new ApiError(409, '검수 표시가 바뀌었습니다'),
    )
    vi.mocked(getReviewSupport).mockRejectedValue(new ApiError(503, '잠시 사용할 수 없습니다'))
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: reviewCapabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    await screen.findAllByText('누락 의심')
    await user.click(screen.getAllByRole('button', { name: '확인했어요' })[0]!)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '최신 검수 상태를 불러오지 못했습니다',
    )
    expect(screen.queryByText(/최신 상태를 불러왔습니다/)).not.toBeInTheDocument()
  })

  it('항목 저장 충돌로 stale 상태를 받으면 옛 revision 재분석을 막는다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    vi.mocked(updateReviewSupportItem).mockRejectedValue(
      new ApiError(409, '검수 표시가 바뀌었습니다'),
    )
    vi.mocked(getReviewSupport).mockResolvedValue(reviewSupportResponse({ status: 'stale' }))
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: reviewCapabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    await screen.findAllByText('누락 의심')
    await user.click(screen.getAllByRole('button', { name: '확인했어요' })[0]!)

    expect(await screen.findByText(/최신 상태를 불러왔습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 분석' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '최신 내용 불러오기' })).toBeInTheDocument()
  })

  it('본문 저장으로 revision이 바뀌면 이전 확인을 stale로 표시한다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(reviewSupportResponse())
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        edited_text: '바뀐 글',
        reviewed_at: '2026-09-18T01:00:00Z',
        content_revision: 2,
        review_capabilities: reviewCapabilities,
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          easy_text: '원래 글',
          content_revision: 1,
          review_capabilities: reviewCapabilities,
        })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('button', { name: /검수할 내용/ }))
    await screen.findAllByText('누락 의심')
    const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    await user.clear(editor)
    await user.type(editor, '바뀐 글')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(
      await screen.findByText('본문이 바뀌었습니다. 확인할 내용을 다시 불러와 주세요.'),
    ).toBeInTheDocument()
    expect(saveReview).toHaveBeenCalledWith('c1', '바뀐 글', 1)
  })
})

describe('ER-25 결과 문단 집중 검토', () => {
  const capabilities = {
    review_support: true,
    action_guide: false,
    table_relations: false,
    review_history: false,
    explanations: false,
    illustrations: false,
    focused_review: true,
  }

  function focusedResponse(items: ReviewItem[], reviewRevision = 1): ReviewSupportResponse {
    return {
      status: 'ready',
      assessment: {
        assessment_id: 'assessment-1',
        content_revision: 1,
        analyzer_version: 'rules-v2',
        review_revision: reviewRevision,
        coverage: 'supported',
        limitations: [],
        items,
      },
    }
  }

  it('자동 분석 후 실제 신호가 있는 문단만 한 마크로 묶고 batch로 확인한다', async () => {
    const user = userEvent.setup()
    const items = [
      reviewItem({ item_id: 'item-1', easy_unit_indexes: [0] }),
      reviewItem({ item_id: 'item-2', easy_unit_indexes: [0], rule_code: 'exception_scope' }),
    ]
    vi.mocked(analyzeReviewSupport).mockResolvedValue(focusedResponse(items))
    vi.mocked(updateReviewSupportItems).mockResolvedValue(
      focusedResponse(
        items.map((item) => ({ ...item, state: 'confirmed', confirmed_by: 'u1' })),
        2,
      ),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          easy_text: '첫 문단\n둘째 문단',
          segment_map: segmentMap({
            source_unit_count: 2,
            units: [
              segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0] }),
              segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1] }),
            ],
          }),
          review_capabilities: capabilities,
        })}
        source={sourceReady('원문 하나\n원문 둘')}
      />,
    )

    expect(await screen.findByText('검토 필요 2개')).toBeInTheDocument()
    expect(screen.getByText('검토 필요한 문단 1개')).toBeInTheDocument()
    expect(screen.queryByText('대응 확인')).not.toBeInTheDocument()
    expect(screen.queryByText('추정')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '문단별 상세 비교' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '이 문단 확인했어요' }))

    expect(updateReviewSupportItems).toHaveBeenCalledWith('c1', {
      assessment_id: 'assessment-1',
      expected_content_revision: 1,
      expected_review_revision: 1,
      item_ids: ['item-1', 'item-2'],
      state: 'confirmed',
      reason: null,
    })
    expect(screen.queryByText('검토 필요 2개')).not.toBeInTheDocument()
    expect(screen.getByText('확인 완료 2개')).toBeInTheDocument()
  })

  it('위치 없는 항목은 결과 상단에 남기고 편집 중에는 낡은 마크를 숨긴다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(
      focusedResponse([
        reviewItem({ item_id: 'mapped', easy_unit_indexes: [0] }),
        reviewItem({ item_id: 'unlocated', easy_unit_indexes: [] }),
      ]),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          easy_text: '첫 문단',
          segment_map: segmentMap({
            units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0] })],
          }),
          review_capabilities: capabilities,
        })}
        source={sourceReady('원문')}
      />,
    )

    expect(await screen.findByText('결과에서 위치를 찾지 못한 검토 항목 1개')).toBeInTheDocument()
    expect(screen.getByText('검토 필요 1개')).toBeInTheDocument()

    await user.type(screen.getByLabelText('쉬운 글 문단 1'), '!')
    expect(screen.getByText('저장하면 검토할 부분을 다시 찾습니다.')).toBeInTheDocument()
    expect(screen.queryByText('검토 필요 1개')).not.toBeInTheDocument()
  })

  it('대응표가 없으면 전체 textarea와 상단 검토 요약을 유지한다', async () => {
    vi.mocked(analyzeReviewSupport).mockResolvedValue(
      focusedResponse([reviewItem({ easy_unit_indexes: [0] })]),
    )
    render(
      <ReviewEditor
        conversion={conversion({ segment_map: null, review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    expect(await screen.findByText('문서 전체에서 확인할 검토 항목 1개')).toBeInTheDocument()
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeInTheDocument()
    expect(screen.queryByLabelText('쉬운 글 문단 1')).not.toBeInTheDocument()
  })

  it('분석이 제한되면 문단 마크 대신 전체 글과 제한 안내를 보여 준다', async () => {
    const response = focusedResponse([reviewItem({ easy_unit_indexes: [0] })])
    response.assessment!.coverage = 'limited'
    response.assessment!.limitations = ['signal_limit']
    vi.mocked(analyzeReviewSupport).mockResolvedValue(response)
    render(
      <ReviewEditor
        conversion={conversion({
          easy_text: '첫 문단',
          segment_map: segmentMap({
            units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0] })],
          }),
          review_capabilities: capabilities,
        })}
        source={sourceReady('원문')}
      />,
    )

    expect(
      await screen.findByText('자동 검사가 일부만 이루어졌습니다. 문서 전체도 확인해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.getByText('문서 전체에서 확인할 검토 항목 1개')).toBeInTheDocument()
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeInTheDocument()
    expect(screen.queryByLabelText('쉬운 글 문단 1')).not.toBeInTheDocument()
  })

  it('분석 요청이 실패하면 본문을 다시 불러오지 않고 재시도할 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport)
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(focusedResponse([]))
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(await screen.findByRole('button', { name: '검토 분석 다시 시도' }))
    await waitFor(() => expect(analyzeReviewSupport).toHaveBeenCalledTimes(2))
    expect(
      await screen.findByText('자동 검사에서 추가 표시를 찾지 못했습니다.'),
    ).toBeInTheDocument()
  })

  it('확인 충돌 후 다른 본문 버전의 분석이 준비됐어도 최신 본문을 불러오게 한다', async () => {
    const user = userEvent.setup()
    vi.mocked(analyzeReviewSupport).mockResolvedValue(
      focusedResponse([reviewItem({ easy_unit_indexes: [] })]),
    )
    vi.mocked(updateReviewSupportItems).mockRejectedValue(new ApiError(409, '본문 버전 충돌'))
    const latest = focusedResponse([])
    latest.assessment!.content_revision = 2
    vi.mocked(getReviewSupport).mockResolvedValue(latest)
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(await screen.findByRole('button', { name: '확인했어요' }))
    expect(
      await screen.findByText('다른 화면에서 저장한 최신 내용과 충돌했습니다.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '최신 내용 불러오기' })).toBeInTheDocument()
  })
})

describe('ER-07 행동 안내 작업 탭', () => {
  const capabilities = {
    review_support: false,
    action_guide: true,
    table_relations: false,
    review_history: false,
    explanations: false,
    illustrations: false,
    focused_review: false,
  }

  it('기능이 꺼져 있으면 새 탭과 API 호출을 만들지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문')} />)

    expect(screen.queryByRole('tab', { name: '행동 안내' })).not.toBeInTheDocument()
    expect(getActionGuide).not.toHaveBeenCalled()
  })

  it('행동 안내 탭을 처음 열 때 조회하고 본문 편집을 숨기며 돌아오면 보존한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuide).mockResolvedValue({
      status: 'not_generated',
      guide: null,
      active_job_id: null,
      latest_job_id: null,
    })
    vi.mocked(listActionGuideJobs).mockResolvedValue({
      active_job: null,
      latest_job: null,
      required_credits: 2,
      available_credits: 10,
    })
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    const guideTab = screen.getByRole('tab', { name: '행동 안내' })
    const bodyTab = screen.getByRole('tab', { name: '본문 검수' })
    const bodyEditor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    expect(getActionGuide).not.toHaveBeenCalled()
    expect(bodyEditor).toBeVisible()

    await user.click(guideTab)
    await waitFor(() => expect(getActionGuide).toHaveBeenCalledWith('c1', expect.anything()))
    expect(guideTab).toHaveAttribute('aria-selected', 'true')
    expect(bodyEditor).not.toBeVisible()

    await user.click(bodyTab)
    expect(bodyEditor).toBeVisible()
    await user.click(guideTab)
    expect(getActionGuide).toHaveBeenCalledTimes(1)
  })
})

describe('R4 표 관계와 R5 검수 기록 작업 탭', () => {
  const capabilities = {
    review_support: false,
    action_guide: false,
    table_relations: true,
    review_history: true,
    explanations: false,
    illustrations: false,
    focused_review: false,
  }

  it('기능 플래그가 없으면 표 관계와 검수 기록을 모두 숨긴다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문')} />)

    expect(screen.queryByRole('tab', { name: '검수 기록' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '표 관계' })).not.toBeInTheDocument()
    expect(getReviewHistory).not.toHaveBeenCalled()
  })

  it('표는 원문 좌표를 읽고 검수 기록은 탭을 연 뒤에만 조회한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getReviewHistory).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      events: [],
      next_cursor: null,
    })
    const table = {
      table_id: 'table-1',
      source_unit_indexes: [0, 1, 2, 3],
      row_count: 2,
      column_count: 2,
      cells: [
        { row: 0, column: 0, source_unit_indexes: [0], header_refs: [] },
        { row: 0, column: 1, source_unit_indexes: [1], header_refs: [] },
        { row: 1, column: 0, source_unit_indexes: [2], header_refs: [0] },
        { row: 1, column: 1, source_unit_indexes: [3], header_refs: [1] },
      ],
      unit_anchors: [4],
      footnote_anchors: [5],
      support_status: 'supported' as const,
      support_reason: null,
    }
    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('구분\n금액\n일반 가구\n10000\n단위: 원\n※ 월 단위', [table])}
      />,
    )

    expect(screen.getByRole('heading', { name: '표 관계' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '구분' })).toBeInTheDocument()
    expect(screen.getByText('단위: 원')).toBeInTheDocument()
    expect(getReviewHistory).not.toHaveBeenCalled()

    await user.click(screen.getByRole('tab', { name: '검수 기록' }))
    await waitFor(() =>
      expect(getReviewHistory).toHaveBeenCalledWith('c1', { limit: 20 }, expect.any(AbortSignal)),
    )
    expect(screen.getByText('아직 검수 기록이 없습니다.')).toBeInTheDocument()
  })

  it('원문을 불러오는 동안에는 표 관계의 미지원 안내를 먼저 보여주지 않는다', () => {
    const view = render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceLoading()}
      />,
    )

    expect(screen.queryByRole('heading', { name: '표 관계' })).not.toBeInTheDocument()
    expect(
      screen.queryByText(
        '이 문서의 표 관계 정보를 확인할 수 없습니다. 원문에서 직접 확인해 주세요.',
      ),
    ).not.toBeInTheDocument()

    view.rerender(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('원문', [])}
      />,
    )

    expect(screen.getByRole('heading', { name: '표 관계' })).toBeInTheDocument()
    expect(screen.getByText('원문에서 확인된 표가 없습니다.')).toBeInTheDocument()
  })

  it('활성화된 검수 기록 기능이 사라지면 본문 검수 탭으로 돌아간다', async () => {
    const user = userEvent.setup()
    vi.mocked(getReviewHistory).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      events: [],
      next_cursor: null,
    })
    const withHistory = {
      ...capabilities,
      action_guide: true,
    }
    const withoutHistory = { ...withHistory, review_history: false }
    const view = render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: withHistory })}
        source={sourceReady('원문')}
      />,
    )

    await user.click(screen.getByRole('tab', { name: '검수 기록' }))
    expect(await screen.findByRole('heading', { name: '검수 기록', level: 1 })).toBeInTheDocument()

    view.rerender(
      <ReviewEditor
        conversion={conversion({ review_capabilities: withoutHistory })}
        source={sourceReady('원문')}
      />,
    )

    expect(screen.getByRole('tab', { name: '본문 검수' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
    expect(screen.queryByRole('heading', { name: '검수 기록', level: 1 })).not.toBeInTheDocument()
  })
})

describe('R6 용어 설명 작업 탭', () => {
  const capabilities = {
    review_support: false,
    action_guide: false,
    table_relations: false,
    review_history: false,
    explanations: true,
    illustrations: false,
    focused_review: false,
  }

  it('기능 플래그가 없으면 용어 설명을 숨긴다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문')} />)

    expect(screen.queryByRole('heading', { name: '용어 설명' })).not.toBeInTheDocument()
    expect(getExplanations).not.toHaveBeenCalled()
  })

  it('기능이 켜져 있으면 용어 설명을 보이고 조회한다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [],
    })

    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    expect(screen.getByRole('heading', { name: '용어 설명' })).toBeInTheDocument()
    await waitFor(() => expect(getExplanations).toHaveBeenCalledWith('c1', expect.any(AbortSignal)))
  })
})

describe('R7 그림 목록 작업 탭', () => {
  const capabilities = {
    review_support: false,
    action_guide: false,
    table_relations: false,
    review_history: false,
    explanations: false,
    illustrations: true,
    focused_review: false,
  }

  it('기능 플래그가 없으면 그림 목록을 숨긴다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문')} />)

    expect(screen.queryByRole('heading', { name: '그림 목록' })).not.toBeInTheDocument()
    expect(getIllustrations).not.toHaveBeenCalled()
  })

  it('기능이 켜져 있으면 그림 목록을 보이고 조회한다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [] })

    render(
      <ReviewEditor
        conversion={conversion({ review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    expect(screen.getByRole('heading', { name: '그림 목록' })).toBeInTheDocument()
    await waitFor(() => expect(getIllustrations).toHaveBeenCalledWith(expect.any(AbortSignal)))
  })
})

describe('ER-16 그림 배치', () => {
  const capabilities = {
    review_support: false,
    action_guide: false,
    table_relations: false,
    review_history: false,
    explanations: false,
    illustrations: true,
    focused_review: false,
  }

  function emptyPlacements(revision: number) {
    return {
      conversion_id: 'c1',
      current_content_revision: revision,
      placements_content_revision: null,
      stale: false,
      placements: [],
    }
  }

  it('기능 플래그가 없으면 그림 배치 패널을 숨긴다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문')} />)

    expect(screen.queryByRole('heading', { name: '그림 배치' })).not.toBeInTheDocument()
    expect(getIllustrationPlacements).not.toHaveBeenCalled()
  })

  it('기능이 켜져 있으면 그림 배치 패널을 보이고 조회한다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(emptyPlacements(1))

    render(
      <ReviewEditor
        conversion={conversion({ content_revision: 1, review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    expect(screen.getByRole('heading', { name: '그림 배치' })).toBeInTheDocument()
    await waitFor(() =>
      expect(getIllustrationPlacements).toHaveBeenCalledWith('c1', expect.any(AbortSignal)),
    )
  })

  it('배치가 하나라도 있고 stale이 아니면 다운로드 버튼 근처에 안내문을 보여준다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({
      illustrations: [
        {
          asset_id: 'visit-office',
          caption: '기관 방문',
          purpose: 'visit_office',
          alt_text: '사람이 건물 입구로 걸어 들어가는 그림',
          license: 'CC0',
          source: '자체 제작',
          reviewed_by: '검수자',
          reviewed_at: '2026-09-01',
          version: 1,
          mapping_examples: [],
          image_url: '/illustrations/visit-office/image',
        },
      ],
    })
    vi.mocked(getIllustrationPlacements).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      placements_content_revision: 1,
      stale: false,
      placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
    })

    render(
      <ReviewEditor
        conversion={conversion({ content_revision: 1, review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    await waitFor(() =>
      expect(
        screen.getByText('배치한 그림은 파일에 들어가지 않습니다. 웹 미리보기에서만 보입니다.'),
      ).toBeInTheDocument(),
    )
  })

  it('배치가 없으면 다운로드 버튼 근처 안내문을 보이지 않는다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(emptyPlacements(1))

    render(
      <ReviewEditor
        conversion={conversion({ content_revision: 1, review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    await waitFor(() => expect(getIllustrationPlacements).toHaveBeenCalled())
    expect(
      screen.queryByText('배치한 그림은 파일에 들어가지 않습니다. 웹 미리보기에서만 보입니다.'),
    ).not.toBeInTheDocument()
  })

  it('배치가 stale이면 다운로드 버튼 근처 안내문을 보이지 않는다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 2,
      placements_content_revision: 1,
      stale: true,
      placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
    })

    render(
      <ReviewEditor
        conversion={conversion({ content_revision: 2, review_capabilities: capabilities })}
        source={sourceReady('원문')}
      />,
    )

    await waitFor(() => expect(getIllustrationPlacements).toHaveBeenCalled())
    expect(
      screen.queryByText('배치한 그림은 파일에 들어가지 않습니다. 웹 미리보기에서만 보입니다.'),
    ).not.toBeInTheDocument()
  })
})
