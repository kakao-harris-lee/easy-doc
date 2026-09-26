import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  applyGuideDraft,
  createActionGuideAnalysisJob,
  createGuideDraft,
  correctGuideAnalysis,
  getActionGuideWorkflow,
  getConversion,
  listGuidePreviousBodies,
  getGuidePreviousBody,
  resolveGuideAnalysisSignal,
  reviewGuideAnalysis,
  reviewGuideDraft,
} from '../api/client'
import type { ActionGuideAnalysis, ActionGuideWorkflow, GuideDraft } from '../api/types'
import { conversion } from '../test/factories'
import {
  ActionGuideWorkflowPanel,
  type ActionGuideWorkflowPanelProps,
} from './ActionGuideWorkflowPanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getActionGuideWorkflow: vi.fn(),
  createActionGuideAnalysisJob: vi.fn(),
  resolveGuideAnalysisSignal: vi.fn(),
  correctGuideAnalysis: vi.fn(),
  reviewGuideAnalysis: vi.fn(),
  createGuideDraft: vi.fn(),
  reviewGuideDraft: vi.fn(),
  applyGuideDraft: vi.fn(),
  getConversion: vi.fn(),
  downloadGuideDraft: vi.fn(),
  listGuidePreviousBodies: vi.fn(),
  getGuidePreviousBody: vi.fn(),
}))

const analysis: ActionGuideAnalysis = {
  schema_version: 2,
  analysis_id: 'analysis-1',
  analysis_revision: 2,
  review_revision: 3,
  based_on_content_revision: 1,
  state: 'current',
  provenance: 'fake',
  reading_level: 'grade_5_6',
  suitability: 'guide',
  action_presence: 'found',
  reason: '문의 행동이 있습니다.',
  evidence: [{ source_unit_indexes: [0], quote: '일주일 전에 문의하세요.' }],
  source_units: [{ id: 0, text: '일주일 전에 문의하세요.' }],
  body_units: [{ id: 0, text: '일주일 전에 문의해 주세요.' }],
  saved_body: '일주일 전에 문의해 주세요.',
  actions: [],
  coverage: [{ source_unit_id: 0, status: 'context', action_ids: [] }],
  signals: [],
  unresolved_signals: [],
  extraction_review_complete: true,
  reviewed: true,
  allowed_modes: ['additional_guide', 'full_document'],
  generation_enabled: true,
  created_at: '2026-09-26T00:00:00Z',
}
const draft: GuideDraft = {
  draft_id: 'draft-1',
  analysis_id: 'analysis-1',
  analysis_revision: 2,
  analysis_review_revision: 3,
  based_on_content_revision: 1,
  draft_revision: 1,
  mode: 'full_document',
  body: '일주일 전에 문의해 주세요.\n\n행동 안내\n문의하세요.',
  blocks: [
    {
      id: 'block-1',
      action_id: 'action-1',
      text: '문의하세요.',
      cautions: ['일주일 전까지'],
      evidence: analysis.evidence,
    },
  ],
  reviewed: false,
  state: 'current',
  created_at: '2026-09-26T00:00:00Z',
}
const workflow: ActionGuideWorkflow = {
  intake_enabled: true,
  generation_enabled: true,
  required_credits: 2,
  available_credits: 10,
  generation_credits: 0,
  analysis,
  active_job: null,
  latest_job: null,
  drafts: [],
}
const props: ActionGuideWorkflowPanelProps = {
  conversionId: 'conversion-1',
  contentRevision: 1,
  savedBody: '일주일 전에 문의해 주세요.',
  bodyDirty: false,
  bodyBusy: false,
  bodyConflict: false,
  onBodyApplying: vi.fn(),
  onBodyApplied: vi.fn(),
  onBodyConflict: vi.fn(),
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getActionGuideWorkflow).mockReset().mockResolvedValue(workflow)
  vi.mocked(createActionGuideAnalysisJob).mockReset()
  vi.mocked(createGuideDraft).mockReset().mockResolvedValue(draft)
  vi.mocked(correctGuideAnalysis).mockReset().mockResolvedValue(analysis)
  vi.mocked(reviewGuideAnalysis).mockReset().mockResolvedValue(analysis)
  vi.mocked(reviewGuideDraft)
    .mockReset()
    .mockResolvedValue({ ...draft, reviewed: true })
  vi.mocked(resolveGuideAnalysisSignal).mockReset().mockResolvedValue(analysis)
  vi.mocked(applyGuideDraft).mockReset()
  vi.mocked(getConversion).mockReset()
  vi.mocked(listGuidePreviousBodies).mockReset().mockResolvedValue([])
  vi.mocked(getGuidePreviousBody).mockReset()
  vi.stubGlobal('crypto', { randomUUID: () => 'request-1' })
})

describe('행동 분석과 보완 흐름', () => {
  it('진입에서는 조회만 하고 분석과 추가 구성 비용을 확인한 뒤 접수한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({ ...workflow, analysis: null })
    vi.mocked(createActionGuideAnalysisJob).mockResolvedValue({
      job_id: 'job-1',
      request_id: 'request-1',
      status: 'queued',
      based_on_content_revision: 1,
      reserved_credits: 2,
      candidate_id: null,
      candidate_state: null,
      content: null,
      failure_code: null,
      created_at: '',
      updated_at: '',
    })
    render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '행동 확인' }))
    expect(createActionGuideAnalysisJob).not.toHaveBeenCalled()
    expect(screen.getByText(/행동 분석 2크레딧 · 이후 안내 구성 추가 비용 0크레딧/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '2크레딧으로 행동 분석' }))
    expect(createActionGuideAnalysisJob).toHaveBeenCalledWith('conversion-1', {
      request_id: 'request-1',
      expected_content_revision: 1,
    })
  })

  it('불확실한 접수는 같은 멱등 키로만 다시 확인한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({ ...workflow, analysis: null })
    vi.mocked(createActionGuideAnalysisJob).mockRejectedValue(new TypeError('연결 끊김'))
    render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '행동 확인' }))
    await user.click(screen.getByRole('button', { name: '2크레딧으로 행동 분석' }))
    await user.click(await screen.findByRole('button', { name: '같은 행동 분석 요청 확인' }))
    expect(createActionGuideAnalysisJob).toHaveBeenCalledTimes(2)
    expect(vi.mocked(createActionGuideAnalysisJob).mock.calls[1]?.[1]).toEqual(
      vi.mocked(createActionGuideAnalysisJob).mock.calls[0]?.[1],
    )
  })

  it('미해결 원문 대응을 일괄 확인하지 못하고 부분·인용·메모를 함께 저장한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: {
        ...analysis,
        reviewed: false,
        signals: [
          {
            id: 'source-0',
            kind: 'source_body',
            source_unit_ids: [0],
            action_id: null,
            detail: '본문에 조건이 유지됐는지 확인',
            resolved: false,
            resolvable: true,
            resolution_note: null,
            body_unit_indexes: [],
            body_quote: null,
          },
        ],
        unresolved_signals: ['source-0'],
      },
    })
    render(<ActionGuideWorkflowPanel {...props} />)
    const signal = await screen.findByRole('region', { name: '본문에 조건이 유지됐는지 확인' })
    expect(screen.getByRole('button', { name: '행동 분석 검토 저장' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '전체 문서 보완 만들기' })).toBeDisabled()
    await user.click(within(signal).getByRole('checkbox', { name: /현재 본문 부분 1/ }))
    expect(within(signal).getByRole('textbox', { name: '현재 본문의 근거 문구' })).toHaveValue(
      '일주일 전에 문의해 주세요.',
    )
    await user.type(
      within(signal).getByRole('textbox', { name: '이 항목을 확인한 내용' }),
      '문의 기한이 본문에도 있습니다.',
    )
    await user.click(within(signal).getByRole('button', { name: '이 항목 검토 저장' }))
    expect(resolveGuideAnalysisSignal).toHaveBeenCalledWith(
      'conversion-1',
      'analysis-1',
      'source-0',
      {
        expected_content_revision: 1,
        expected_analysis_revision: 2,
        expected_review_revision: 3,
        note: '문의 기한이 본문에도 있습니다.',
        body_unit_indexes: [0],
        body_quote: '일주일 전에 문의해 주세요.',
      },
    )
  })

  it('내용상 허용 모드가 없으면 신청 절차나 생성 버튼을 임의로 만들지 않는다', async () => {
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: { ...analysis, action_presence: 'none', allowed_modes: [] },
    })
    render(<ActionGuideWorkflowPanel {...props} />)
    expect(await screen.findByText('원문에서 할 일을 찾지 못했습니다.')).toBeVisible()
    expect(screen.queryByRole('button', { name: '전체 문서 보완 만들기' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '단계별 추가 안내 만들기' }),
    ).not.toBeInTheDocument()
  })

  it('추가 안내를 독립 저장하고 본문 반영 요청을 보내지 않는다', async () => {
    const user = userEvent.setup()
    render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '단계별 추가 안내 만들기' }))
    expect(createGuideDraft).toHaveBeenCalledWith('conversion-1', {
      expected_content_revision: 1,
      expected_analysis_revision: 2,
      expected_review_revision: 3,
      request_id: 'request-1',
      analysis_id: 'analysis-1',
      mode: 'additional_guide',
    })
    expect(applyGuideDraft).not.toHaveBeenCalled()
  })

  it('본문 대응만 미해결이면 서버가 허용한 추가 안내를 열고 전체 보완은 열지 않는다', async () => {
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: {
        ...analysis,
        allowed_modes: ['additional_guide'],
        unresolved_signals: ['source-0'],
        signals: [
          {
            id: 'source-0',
            kind: 'source_body',
            source_unit_ids: [0],
            action_id: null,
            detail: '본문 대응 확인',
            resolved: false,
            resolvable: true,
            resolution_note: null,
            body_unit_indexes: [],
            body_quote: null,
          },
        ],
      },
    })
    render(<ActionGuideWorkflowPanel {...props} />)
    expect(await screen.findByRole('button', { name: '단계별 추가 안내 만들기' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '전체 문서 보완 만들기' })).not.toBeInTheDocument()
  })

  it('빠진 행동을 원문 근거와 전체 대응에 연결해 수정하고 이전 검토를 승계하지 않는다', async () => {
    const user = userEvent.setup()
    const onDirtyChange = vi.fn()
    render(<ActionGuideWorkflowPanel {...props} onDirtyChange={onDirtyChange} />)
    await user.click(await screen.findByRole('button', { name: '행동과 근거 수정' }))
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    await user.click(screen.getByRole('button', { name: '원문에서 빠진 행동 추가' }))
    const instruction = screen.getByRole('group', { name: '할 일' })
    await user.type(
      within(instruction).getByRole('textbox', { name: '할 일 내용' }),
      '일주일 전에 문의하세요.',
    )
    await user.click(within(instruction).getByRole('button', { name: '근거 추가' }))
    await user.selectOptions(
      within(instruction).getByRole('combobox', { name: '할 일 근거 1 원문 부분' }),
      '0',
    )
    const sourcePart = screen.getByRole('group', { name: '원문 부분 1' })
    await user.selectOptions(within(sourcePart).getByRole('combobox'), 'action')
    await user.click(within(sourcePart).getByRole('checkbox', { name: '일주일 전에 문의하세요.' }))
    await user.click(screen.getByRole('button', { name: '분석 수정 저장' }))
    expect(correctGuideAnalysis).toHaveBeenCalledWith(
      'conversion-1',
      'analysis-1',
      expect.objectContaining({
        expected_analysis_revision: 2,
        expected_review_revision: 3,
        actions: [
          expect.objectContaining({
            id: 'request-1',
            instruction: {
              status: 'present',
              text: '일주일 전에 문의하세요.',
              evidence: analysis.evidence,
            },
          }),
        ],
        coverage: [{ source_unit_id: 0, status: 'action', action_ids: ['request-1'] }],
      }),
    )
    expect(reviewGuideAnalysis).not.toHaveBeenCalled()
  })

  it('전체 보완은 모든 블록 검토와 반영 확인 후 CAS로 적용하고 미확인 최신 본문을 부모에게 전달한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({ ...workflow, drafts: [draft] })
    render(<ActionGuideWorkflowPanel {...props} />)
    const preview = await screen.findByRole('region', { name: '전체 문서 보완 미리보기' })
    expect(within(preview).getByRole('button', { name: '전체 본문에 반영' })).toBeDisabled()
    expect(within(preview).getByText('주의: 일주일 전까지')).toBeVisible()
    await user.click(
      within(preview).getByRole('checkbox', { name: '이 행동 안내와 조건을 확인했습니다.' }),
    )
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      drafts: [{ ...draft, reviewed: true }],
    })
    await user.click(within(preview).getByRole('button', { name: '안내 검토 저장' }))
    expect(reviewGuideDraft).toHaveBeenCalledWith('conversion-1', 'draft-1', {
      expected_content_revision: 1,
      expected_analysis_revision: 2,
      expected_review_revision: 3,
      expected_draft_revision: 1,
      confirmed_block_ids: ['block-1'],
    })
    await waitFor(() =>
      expect(
        within(preview).getByRole('checkbox', { name: /이 보완본을 전체 본문에 반영/ }),
      ).toBeEnabled(),
    )
    await user.click(
      within(preview).getByRole('checkbox', { name: /이 보완본을 전체 본문에 반영/ }),
    )
    const latest = conversion({
      status: 'done',
      content_revision: 2,
      reviewed_at: null,
      edited_text: draft.body,
    })
    vi.mocked(getConversion).mockResolvedValue(latest)
    vi.mocked(applyGuideDraft).mockResolvedValue({
      content_revision: 2,
      previous_snapshot_id: 'snapshot-1',
      replayed: false,
    })
    await user.click(within(preview).getByRole('button', { name: '전체 본문에 반영' }))
    expect(applyGuideDraft).toHaveBeenCalledWith('conversion-1', 'draft-1', {
      request_id: 'request-1',
      expected_content_revision: 1,
      expected_analysis_revision: 2,
      expected_review_revision: 3,
      expected_draft_revision: 1,
    })
    expect(props.onBodyApplying).toHaveBeenCalledWith(true)
    await waitFor(() => expect(props.onBodyApplied).toHaveBeenCalledWith(latest))
    expect(props.onBodyApplying).toHaveBeenLastCalledWith(false)
  })

  it.each([
    { bodyDirty: true },
    { bodyBusy: true },
    { bodyConflict: true },
    { contentRevision: 2 },
  ])('본문 상태가 안전하지 않으면 구성과 반영을 막는다: %j', async (overrides) => {
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      drafts: [{ ...draft, reviewed: true }],
    })
    render(<ActionGuideWorkflowPanel {...props} {...overrides} />)
    expect(await screen.findByRole('button', { name: '전체 문서 보완 만들기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '전체 본문에 반영' })).toBeDisabled()
  })

  it('새 요청이 꺼져도 저장 자료를 보여 준다', async () => {
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      intake_enabled: false,
      generation_enabled: false,
      drafts: [{ ...draft, mode: 'additional_guide', reviewed: true }],
    })
    render(<ActionGuideWorkflowPanel {...props} />)
    expect(await screen.findByRole('region', { name: '단계별 추가 안내 미리보기' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '행동 다시 확인' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '전체 문서 보완 만들기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '단계별 추가 안내 TXT 내려받기' })).toBeEnabled()
  })

  it('다른 버전의 분석을 불러오면 이전 확인 체크를 새 버전에 넘기지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: { ...analysis, reviewed: false },
    })
    render(<ActionGuideWorkflowPanel {...props} />)
    const checkbox = await screen.findByRole('checkbox', {
      name: '이 분석의 행동·조건·미기재 정보를 원문과 대조했습니다.',
    })
    await user.click(checkbox)
    expect(checkbox).toBeChecked()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: { ...analysis, reviewed: false, analysis_revision: 3 },
    })
    await user.click(screen.getByRole('button', { name: '행동 분석 상태 새로고침' }))
    await waitFor(() => expect(checkbox).not.toBeChecked())
    expect(screen.getByRole('button', { name: '행동 분석 검토 저장' })).toBeDisabled()
  })

  it('새 요청이 꺼져도 이전 본문을 열어 복사할 수 있고 자동 복구하지 않는다', async () => {
    const user = userEvent.setup()
    const copy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      intake_enabled: false,
      generation_enabled: false,
    })
    vi.mocked(listGuidePreviousBodies).mockResolvedValue([
      {
        snapshot_id: 'previous-1',
        draft_id: 'draft-1',
        previous_content_revision: 1,
        applied_content_revision: 2,
      },
    ])
    vi.mocked(getGuidePreviousBody).mockResolvedValue({ body: '배경과 목적이 남아 있는 이전 본문' })
    render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(screen.getByRole('button', { name: '이전 본문 목록 열기' }))
    await user.click(await screen.findByRole('button', { name: '본문 버전 1 열기' }))
    expect(await screen.findByRole('textbox', { name: '선택한 이전 본문' })).toHaveValue(
      '배경과 목적이 남아 있는 이전 본문',
    )
    await user.click(screen.getByRole('button', { name: '이전 본문 복사' }))
    expect(copy).toHaveBeenCalledWith('배경과 목적이 남아 있는 이전 본문')
    expect(applyGuideDraft).not.toHaveBeenCalled()
    expect(props.onBodyApplied).not.toHaveBeenCalled()
    expect(screen.getByText(/본문 검수 편집기에 붙여 넣고 확인해 주세요/)).toBeVisible()
  })

  it('전체 적용 충돌은 부모에 알리고 로컬 본문을 바꾸지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      drafts: [{ ...draft, reviewed: true }],
    })
    vi.mocked(applyGuideDraft).mockRejectedValue(new ApiError(409, '본문 변경'))
    render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(await screen.findByRole('checkbox', { name: /이 보완본을 전체 본문에 반영/ }))
    await user.click(screen.getByRole('button', { name: '전체 본문에 반영' }))
    await waitFor(() => expect(props.onBodyConflict).toHaveBeenCalled())
    expect(props.onBodyApplied).not.toHaveBeenCalled()
    expect(await screen.findByRole('alert')).toHaveTextContent('현재 내용을 유지')
  })

  it('분석 검토 응답이 늦게 와도 이동한 다른 문서의 분석을 덮어쓰지 않는다', async () => {
    const user = userEvent.setup()
    let finishReview: ((value: ActionGuideAnalysis) => void) | undefined
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: { ...analysis, reviewed: false },
    })
    vi.mocked(reviewGuideAnalysis).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishReview = resolve
        }),
    )
    const view = render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(
      await screen.findByRole('checkbox', {
        name: '이 분석의 행동·조건·미기재 정보를 원문과 대조했습니다.',
      }),
    )
    await user.click(screen.getByRole('button', { name: '행동 분석 검토 저장' }))
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: { ...analysis, analysis_id: 'analysis-2', reason: '다른 문서의 분석입니다.' },
    })
    view.rerender(<ActionGuideWorkflowPanel {...props} conversionId="conversion-2" />)
    expect(await screen.findByText('다른 문서의 분석입니다.')).toBeVisible()
    await act(async () => {
      finishReview?.({ ...analysis, reason: '이전 문서에서 늦게 온 분석입니다.' })
    })
    expect(screen.getByText('다른 문서의 분석입니다.')).toBeVisible()
    expect(screen.queryByText('이전 문서에서 늦게 온 분석입니다.')).not.toBeInTheDocument()
  })

  it('전체 반영 중 다른 문서로 이동하면 이전 응답을 새 본문에 반영하지 않는다', async () => {
    const user = userEvent.setup()
    let finishApply:
      | ((value: {
          content_revision: number
          previous_snapshot_id: string
          replayed: boolean
        }) => void)
      | undefined
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      drafts: [{ ...draft, reviewed: true }],
    })
    vi.mocked(applyGuideDraft).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishApply = resolve
        }),
    )
    const view = render(<ActionGuideWorkflowPanel {...props} />)
    await user.click(await screen.findByRole('checkbox', { name: /이 보완본을 전체 본문에 반영/ }))
    await user.click(screen.getByRole('button', { name: '전체 본문에 반영' }))
    vi.mocked(getActionGuideWorkflow).mockResolvedValue({
      ...workflow,
      analysis: { ...analysis, analysis_id: 'analysis-2', reason: '새로 연 문서입니다.' },
      drafts: [],
    })
    view.rerender(<ActionGuideWorkflowPanel {...props} conversionId="conversion-2" />)
    expect(await screen.findByText('새로 연 문서입니다.')).toBeVisible()
    await act(async () => {
      finishApply?.({ content_revision: 2, previous_snapshot_id: 'previous-1', replayed: false })
    })
    expect(props.onBodyApplied).not.toHaveBeenCalled()
    expect(getConversion).not.toHaveBeenCalled()
    expect(screen.queryByText(/전체 보완을 본문에 반영했습니다/)).not.toBeInTheDocument()
  })
})
