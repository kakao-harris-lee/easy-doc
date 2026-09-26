import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  createActionGuideJob,
  downloadActionGuide,
  getActionGuide,
  getActionGuideJob,
  listActionGuideJobs,
  saveActionGuide,
} from '../api/client'
import type {
  ActionGuide,
  ActionGuideContent,
  ActionGuideJob,
  ActionGuideJobCollection,
  ActionGuideResource,
} from '../api/types'
import { sourceReady } from '../test/factories'
import { ActionGuidePanel, type ActionGuidePanelProps } from './ActionGuidePanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  createActionGuideJob: vi.fn(),
  downloadActionGuide: vi.fn(),
  getActionGuide: vi.fn(),
  getActionGuideJob: vi.fn(),
  listActionGuideJobs: vi.fn(),
  saveActionGuide: vi.fn(),
}))

const KINDS = ['eligibility', 'benefits', 'documents', 'steps', 'exceptions', 'contact'] as const
const content: ActionGuideContent = {
  schema_version: 1,
  sections: KINDS.map((kind) => ({ kind, status: 'not_in_source', items: [] })),
}
const guide: ActionGuide = {
  guide_id: 'guide-1',
  based_on_content_revision: 1,
  guide_revision: 1,
  status: 'draft',
  content,
  reviewed_at: null,
  reviewed_by: null,
}
const resource: ActionGuideResource = {
  status: 'not_generated',
  guide: null,
  active_job_id: null,
  latest_job_id: null,
}
const jobs: ActionGuideJobCollection = {
  active_job: null,
  latest_job: null,
  required_credits: 2,
  available_credits: 10,
}
const candidate: ActionGuideJob = {
  job_id: 'job-1',
  request_id: 'request-1',
  status: 'succeeded',
  based_on_content_revision: 1,
  reserved_credits: 2,
  failure_code: null,
  created_at: '2026-09-20T00:00:00Z',
  updated_at: '2026-09-20T00:00:01Z',
  candidate_id: 'candidate-1',
  candidate_state: 'current',
  content,
}

const defaults: ActionGuidePanelProps = {
  conversionId: 'conversion-1',
  contentRevision: 1,
  bodyDirty: false,
  bodyBusy: false,
  bodyConflict: false,
  source: sourceReady('원문 첫 줄\n원문 둘째 줄'),
  onSaveBody: vi.fn().mockResolvedValue(2),
}

beforeEach(() => {
  vi.mocked(getActionGuide).mockReset().mockResolvedValue(resource)
  vi.mocked(listActionGuideJobs).mockReset().mockResolvedValue(jobs)
  vi.mocked(getActionGuideJob).mockReset().mockResolvedValue(candidate)
  vi.mocked(createActionGuideJob).mockReset()
  vi.mocked(saveActionGuide).mockReset()
  vi.mocked(downloadActionGuide).mockReset()
  vi.stubGlobal('crypto', { randomUUID: () => 'generated-uuid' })
})

function show(overrides: Partial<ActionGuidePanelProps> = {}) {
  return render(<ActionGuidePanel {...defaults} {...overrides} />)
}

describe('행동 안내 화면', () => {
  it('생성 전 이용량과 정책을 보여주고 확인 후 한 번만 생성한다', async () => {
    const user = userEvent.setup()
    vi.mocked(createActionGuideJob).mockResolvedValue({
      job: {
        ...candidate,
        status: 'queued',
        candidate_id: null,
        candidate_state: null,
        content: null,
      },
      creditBalance: 8,
    })
    show()
    expect(
      await screen.findByText('필요 이용량 2크레딧 / 남은 이용량 10크레딧'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '안내문 만들기' }))
    expect(createActionGuideJob).not.toHaveBeenCalled()
    expect(screen.getByRole('group', { name: '안내문 생성 확인' })).toHaveAccessibleDescription(
      /필요 이용량 2크레딧.*초안이 만들어지면 이용량이 사용됩니다/,
    )
    await user.click(screen.getByRole('button', { name: '2크레딧으로 안내문 만들기' }))
    expect(createActionGuideJob).toHaveBeenCalledWith('conversion-1', {
      request_id: 'generated-uuid',
      expected_content_revision: 1,
      expected_guide_revision: null,
    })
    expect(
      await screen.findByText('안내문을 만들고 있어요. 다른 화면으로 이동해도 계속됩니다.'),
    ).toBeInTheDocument()
  })

  it('소수 첫째 자리 행동 안내 비용과 잔액을 표시한다', async () => {
    vi.mocked(listActionGuideJobs).mockResolvedValue({
      ...jobs,
      required_credits: 0.1,
      available_credits: 1.1,
    })

    show()

    expect(
      await screen.findByText('필요 이용량 0.1크레딧 / 남은 이용량 1.1크레딧'),
    ).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '안내문 만들기' }))
    expect(screen.getByRole('button', { name: '0.1크레딧으로 안내문 만들기' })).toBeInTheDocument()
  })

  it('이용량이 부족하면 생성 대신 이용량 화면 이동을 제공한다', async () => {
    vi.mocked(listActionGuideJobs).mockResolvedValue({ ...jobs, available_credits: 1 })
    show()

    expect(await screen.findByText('필요 이용량 2크레딧 / 남은 이용량 1크레딧')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '안내문 만들기' })).toBeDisabled()
    expect(screen.getByRole('link', { name: '이용량 화면으로 이동' })).toHaveAttribute(
      'href',
      '/usage',
    )
  })

  it('본문이 dirty이면 저장 성공 뒤 새 버전으로 요청한다', async () => {
    const user = userEvent.setup()
    const onSaveBody = vi.fn().mockResolvedValue(2)
    vi.mocked(getActionGuide).mockResolvedValue(resource)
    vi.mocked(createActionGuideJob).mockResolvedValue({
      job: { ...candidate, status: 'queued' },
      creditBalance: 8,
    })
    show({ bodyDirty: true, onSaveBody })
    await user.click(await screen.findByRole('button', { name: '안내문 만들기' }))
    await user.click(screen.getByRole('button', { name: '본문 저장 후 만들기' }))
    expect(onSaveBody).toHaveBeenCalledTimes(1)
    expect(createActionGuideJob).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({ expected_content_revision: 2 }),
    )
  })

  it('본문 저장 뒤 필요 이용량이 달라지면 새 금액을 다시 확인한 뒤 접수한다', async () => {
    const user = userEvent.setup()
    vi.mocked(listActionGuideJobs)
      .mockResolvedValueOnce(jobs)
      .mockResolvedValueOnce({ ...jobs, required_credits: 3 })
      .mockResolvedValue({ ...jobs, required_credits: 3 })
    vi.mocked(createActionGuideJob).mockResolvedValue({
      job: { ...candidate, status: 'queued' },
      creditBalance: 7,
    })
    show({ bodyDirty: true, onSaveBody: vi.fn().mockResolvedValue(2) })
    await user.click(await screen.findByRole('button', { name: '안내문 만들기' }))
    await user.click(screen.getByRole('button', { name: '본문 저장 후 만들기' }))
    expect(await screen.findByText(/필요 이용량이 바뀌었습니다/)).toBeInTheDocument()
    expect(createActionGuideJob).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '3크레딧으로 안내문 만들기' }))
    expect(createActionGuideJob).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({ expected_content_revision: 2 }),
    )
  })

  it('다른 탭에서 본문을 저장한 뒤에도 새 이용량을 확인받고 접수한다', async () => {
    const user = userEvent.setup()
    vi.mocked(listActionGuideJobs)
      .mockResolvedValueOnce(jobs)
      .mockResolvedValueOnce({ ...jobs, required_credits: 3 })
      .mockResolvedValue({ ...jobs, required_credits: 3 })
    vi.mocked(createActionGuideJob).mockResolvedValue({
      job: { ...candidate, status: 'queued' },
      creditBalance: 7,
    })
    const { rerender } = show()
    await screen.findByText('필요 이용량 2크레딧 / 남은 이용량 10크레딧')

    rerender(<ActionGuidePanel {...defaults} contentRevision={2} />)
    await user.click(screen.getByRole('button', { name: '안내문 만들기' }))
    await user.click(screen.getByRole('button', { name: '2크레딧으로 안내문 만들기' }))

    expect(await screen.findByText(/필요 이용량이 바뀌었습니다/)).toBeInTheDocument()
    expect(createActionGuideJob).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '3크레딧으로 안내문 만들기' }))
    expect(createActionGuideJob).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({ expected_content_revision: 2 }),
    )
  })

  it('본문 저장 뒤 상태 조회 실패 시 POST하지 않고 재조회 성공 후 최신 안내 revision을 사용한다', async () => {
    const user = userEvent.setup()
    const onSaveBody = vi.fn().mockResolvedValue(2)
    vi.mocked(getActionGuide)
      .mockResolvedValueOnce(resource)
      .mockRejectedValueOnce(new ApiError(503, '조회 실패'))
      .mockResolvedValueOnce({
        ...resource,
        status: 'stale',
        guide: { ...guide, guide_revision: 4 },
      })
    vi.mocked(createActionGuideJob).mockResolvedValue({
      job: { ...candidate, status: 'queued' },
      creditBalance: 8,
    })
    show({ bodyDirty: true, onSaveBody })
    await user.click(await screen.findByRole('button', { name: '안내문 만들기' }))
    await user.click(screen.getByRole('button', { name: '본문 저장 후 만들기' }))
    expect(
      await screen.findByText(/본문은 저장됐지만 최신 안내문 상태와 이용량을 확인하지 못했습니다/),
    ).toBeInTheDocument()
    expect(onSaveBody).toHaveBeenCalledTimes(1)
    expect(createActionGuideJob).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '2크레딧으로 안내문 만들기' }))
    await waitFor(() => expect(createActionGuideJob).toHaveBeenCalledTimes(1))
    expect(onSaveBody).toHaveBeenCalledTimes(1)
    expect(createActionGuideJob).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({
        expected_content_revision: 2,
        expected_guide_revision: 4,
      }),
    )
  })

  it('생성 확인으로 초점을 옮기고 Escape 취소 뒤 시작 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    show()
    const trigger = await screen.findByRole('button', { name: '안내문 만들기' })
    await user.click(trigger)
    expect(screen.getByRole('button', { name: '2크레딧으로 안내문 만들기' })).toHaveFocus()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.getByRole('button', { name: '안내문 만들기' })).toHaveFocus())
    expect(createActionGuideJob).not.toHaveBeenCalled()
  })

  it('재방문한 성공 작업은 후보로만 보여주고 명시적 적용 전 저장하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, latest_job_id: 'job-1' })
    vi.mocked(listActionGuideJobs).mockResolvedValue({ ...jobs, latest_job: candidate })
    vi.mocked(saveActionGuide).mockResolvedValue({ ...resource, status: 'draft', guide })
    show()
    expect(
      await screen.findByRole('heading', { name: '행동 안내 보조자료 미리보기' }),
    ).toBeInTheDocument()
    expect(saveActionGuide).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '초안 사용' }))
    expect(saveActionGuide).toHaveBeenCalledWith('conversion-1', {
      candidate_id: 'candidate-1',
      expected_content_revision: 1,
      expected_guide_revision: null,
      content,
      mark_reviewed: false,
    })
    expect(await screen.findByRole('button', { name: '담당자 확인 저장' })).toBeDisabled()
    await user.click(
      screen.getByRole('checkbox', { name: '원문과 비교하여 이 안내문의 내용을 확인했습니다.' }),
    )
    expect(screen.getByRole('button', { name: '담당자 확인 저장' })).toBeEnabled()
  })

  it('행동 하나의 주의사항과 모든 원문 근거를 적용 전에 보여주고 적용 후에도 보존한다', async () => {
    const user = userEvent.setup()
    const action = {
      text: '상영을 원하면 문의하세요.',
      cautions: ['희망 상영일 최소 1주 전에 문의하세요.', '공휴일에는 문의를 받지 않습니다.'],
      source_anchors: [
        { source_unit_indexes: [0], quote: '원문 첫 줄' },
        { source_unit_indexes: [1], quote: '원문 둘째 줄' },
      ],
    }
    const candidateContent: ActionGuideContent = {
      ...content,
      sections: content.sections.map((section) =>
        section.kind === 'steps' ? { ...section, status: 'available', items: [action] } : section,
      ),
    }
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, latest_job_id: 'job-1' })
    vi.mocked(getActionGuideJob).mockResolvedValue({ ...candidate, content: candidateContent })
    vi.mocked(saveActionGuide).mockResolvedValue({
      ...resource,
      status: 'draft',
      guide: { ...guide, content: candidateContent },
    })
    show()

    const preview = await screen.findByRole('region', { name: '행동 안내 보조자료 미리보기' })
    expect(within(preview).getByText(/문서 전체의 내용을 담고 있지는 않습니다/)).toBeVisible()
    const steps = within(preview).getByRole('region', { name: '신청 순서' })
    expect(within(steps).getAllByRole('listitem')).toHaveLength(1)
    expect(within(steps).getByText(action.text)).toBeVisible()
    for (const caution of action.cautions) {
      expect(within(steps).getByText(caution)).toBeVisible()
    }
    await user.click(within(steps).getByText('원문 근거 보기 (2개)'))
    expect(within(steps).getByText('원문 1행: 원문 첫 줄')).toBeVisible()
    expect(within(steps).getByText('원문 2행: 원문 둘째 줄')).toBeVisible()
    expect(saveActionGuide).not.toHaveBeenCalled()

    await user.click(within(preview).getByRole('button', { name: '초안 사용' }))
    expect(saveActionGuide).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({ content: candidateContent, mark_reviewed: false }),
    )
    expect(await screen.findByRole('textbox', { name: '신청 순서 1번 내용' })).toHaveValue(
      action.text,
    )
    expect(screen.getByRole('textbox', { name: '신청 순서 1번 주의할 점' })).toHaveValue(
      action.cautions.join('\n'),
    )
    expect(screen.getByText('원문 1행: 원문 첫 줄')).toBeVisible()
    expect(screen.getByText('원문 2행: 원문 둘째 줄')).toBeVisible()
  })

  it('내용이 있는 확인 필요와 근거 없는 항목을 구분하고 여러 항목을 합치지 않는다', async () => {
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, latest_job_id: 'job-1' })
    vi.mocked(getActionGuideJob).mockResolvedValue({
      ...candidate,
      content: {
        ...content,
        sections: content.sections.map((section) =>
          section.kind === 'steps'
            ? {
                ...section,
                status: 'needs_review',
                items: [
                  { text: '문의하세요.', cautions: [], source_anchors: [] },
                  { text: '담당자에게 확인하세요.', cautions: [], source_anchors: [] },
                ],
              }
            : section,
        ),
      },
    })
    show()

    const preview = await screen.findByRole('region', { name: '행동 안내 보조자료 미리보기' })
    const steps = within(preview).getByRole('region', { name: '신청 순서' })
    expect(within(steps).getByText('확인 필요', { exact: true })).toBeVisible()
    const items = within(steps).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    for (const [index, item] of items.entries()) {
      expect(
        within(item).getByText(index === 0 ? '문의하세요.' : '담당자에게 확인하세요.'),
      ).toBeVisible()
      expect(within(item).getByText('원문 근거 확인 필요')).toBeVisible()
    }
    expect(steps.querySelector('ol')).toBeNull()
    expect(
      within(steps).queryByText('문의하세요. / 담당자에게 확인하세요.'),
    ).not.toBeInTheDocument()
    expect(
      within(within(preview).getByRole('region', { name: '문의할 곳' })).getByText(
        '원문에 안내 없음',
      ),
    ).toBeVisible()
  })

  it.each<Partial<ActionGuidePanelProps>>([
    { bodyDirty: true },
    { bodyBusy: true },
    { bodyConflict: true },
    { contentRevision: 2 },
  ])('본문 변경 또는 충돌 중인 후보는 적용할 수 없다: %j', async (props) => {
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, latest_job_id: 'job-1' })
    show(props)
    expect(await screen.findByRole('button', { name: '초안 사용' })).toBeDisabled()
    expect(saveActionGuide).not.toHaveBeenCalled()
  })

  it('이전 본문 후보는 미리보기와 적용 대신 다시 만들기 안내를 보여준다', async () => {
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, latest_job_id: 'job-1' })
    vi.mocked(getActionGuideJob).mockResolvedValue({ ...candidate, candidate_state: 'stale' })
    show()
    expect(await screen.findByText(/이 후보는 이전 본문을 기준으로 만들어져/)).toBeVisible()
    expect(
      screen.queryByRole('region', { name: '행동 안내 보조자료 미리보기' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '초안 사용' })).not.toBeInTheDocument()
  })

  it('미확인 항목과 근거 없는 내용은 확인 저장을 막고 초안 저장은 허용한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuide).mockResolvedValue({
      ...resource,
      status: 'draft',
      guide: {
        ...guide,
        content: {
          ...content,
          sections: content.sections.map((section, index) =>
            index === 0
              ? {
                  ...section,
                  status: 'needs_review',
                  items: [{ text: '신청하세요', cautions: [], source_anchors: [] }],
                }
              : section,
          ),
        },
      },
    })
    vi.mocked(saveActionGuide).mockResolvedValue({ ...resource, status: 'draft', guide })
    show()
    expect(await screen.findByRole('button', { name: '담당자 확인 저장' })).toBeDisabled()
    await user.type(screen.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }), ' 지금')
    await user.click(screen.getByRole('button', { name: '안내문 저장' }))
    expect(saveActionGuide).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({ mark_reviewed: false }),
    )
  })

  it('편집 후 초안을 먼저 저장하고 담당자 확인을 별도로 저장한다', async () => {
    const user = userEvent.setup()
    const onDirtyChange = vi.fn()
    const editedContent: ActionGuideContent = {
      ...content,
      sections: content.sections.map((section, index) =>
        index === 0
          ? {
              ...section,
              status: 'available',
              items: [
                {
                  text: '신청하세요',
                  cautions: [],
                  source_anchors: [{ source_unit_indexes: [0], quote: '원문 첫 줄' }],
                },
              ],
            }
          : section,
      ),
    }
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, status: 'draft', guide })
    vi.mocked(saveActionGuide)
      .mockResolvedValueOnce({
        ...resource,
        status: 'draft',
        guide: { ...guide, guide_revision: 2, content: editedContent },
      })
      .mockResolvedValueOnce({
        ...resource,
        status: 'reviewed',
        guide: { ...guide, guide_revision: 3, content: editedContent, status: 'reviewed' },
      })
    show({ onDirtyChange })
    await user.selectOptions(
      await screen.findByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' }),
      'needs_review',
    )
    await user.click(screen.getByRole('button', { name: '항목 추가' }))
    await user.type(
      screen.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }),
      '신청하세요',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '신청할 수 있는 사람 1번 원문 근거' }),
      '0',
    )
    await user.selectOptions(
      screen.getByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' }),
      'available',
    )
    expect(onDirtyChange).toHaveBeenCalledWith(true)
    expect(screen.getByRole('button', { name: '담당자 확인 저장' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '안내문 저장' }))
    await waitFor(() => expect(onDirtyChange).toHaveBeenCalledWith(false))
    expect(saveActionGuide).toHaveBeenCalledTimes(1)
    expect(vi.mocked(saveActionGuide).mock.calls.at(0)?.[1].mark_reviewed).toBe(false)
    await user.click(
      screen.getByRole('checkbox', { name: '원문과 비교하여 이 안내문의 내용을 확인했습니다.' }),
    )
    await user.click(screen.getByRole('button', { name: '담당자 확인 저장' }))
    expect(saveActionGuide).toHaveBeenCalledWith(
      'conversion-1',
      expect.objectContaining({ expected_guide_revision: 2, mark_reviewed: true }),
    )
  })

  it('확인된 현재 저장본만 내려받고 stale이면 비활성화한다', async () => {
    vi.mocked(getActionGuide).mockResolvedValue({
      ...resource,
      status: 'reviewed',
      guide: { ...guide, status: 'reviewed', guide_revision: 3 },
    })
    const { rerender } = show()
    expect(await screen.findByRole('button', { name: 'TXT로 내려받기' })).toBeEnabled()
    rerender(<ActionGuidePanel {...defaults} contentRevision={2} />)
    expect(screen.getByText(/이전 본문으로 만든 안내문입니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toBeDisabled()
  })

  it('409 저장 충돌 후 편집 내용은 유지하고 본문 충돌로 오인하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, status: 'draft', guide })
    vi.mocked(saveActionGuide).mockRejectedValue(new ApiError(409, '충돌'))
    show()
    const select = await screen.findByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' })
    await user.selectOptions(select, 'needs_review')
    await user.click(screen.getByRole('button', { name: '항목 추가' }))
    await user.type(
      screen.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }),
      '신청하세요',
    )
    await user.click(screen.getByRole('button', { name: '안내문 저장' }))
    await screen.findByText(/다른 화면에서 안내문이 바뀌었습니다. 현재 편집 내용은 보존/)
    expect(screen.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' })).toHaveValue(
      '신청하세요',
    )
  })

  it('409 뒤 새로고침해도 로컬 편집을 최신 revision으로 재저장하지 않는다', async () => {
    const user = userEvent.setup()
    const newerGuide = { ...guide, guide_revision: 2 }
    vi.mocked(getActionGuide)
      .mockResolvedValueOnce({ ...resource, status: 'draft', guide })
      .mockResolvedValue({ ...resource, status: 'draft', guide: newerGuide })
    vi.mocked(saveActionGuide).mockRejectedValueOnce(new ApiError(409, '충돌'))
    show()
    await user.selectOptions(
      await screen.findByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' }),
      'needs_review',
    )
    await user.click(screen.getByRole('button', { name: '항목 추가' }))
    await user.type(
      screen.getByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }),
      '보존할 편집',
    )
    await user.click(screen.getByRole('button', { name: '안내문 저장' }))
    await screen.findByRole('button', { name: '현재 편집 내용 복사' })
    await user.click(screen.getByRole('button', { name: '상태 새로고침' }))
    expect(
      (screen.getByRole('textbox', { name: '보존된 안내문 편집 내용' }) as HTMLTextAreaElement)
        .value,
    ).toContain('보존할 편집')
    expect(screen.getByRole('button', { name: '안내문 저장' })).toBeDisabled()
    expect(saveActionGuide).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '최신 안내문으로 다시 시작' }))
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '현재 편집 내용 복사' })).not.toBeInTheDocument(),
    )
    expect(
      screen.queryByRole('textbox', { name: '신청할 수 있는 사람 1번 내용' }),
    ).not.toBeInTheDocument()
  })

  it('안내문 PUT을 기다리는 동안 새 입력을 잠그고 응답으로 편집을 덮어쓰지 않는다', async () => {
    const user = userEvent.setup()
    const onDirtyChange = vi.fn()
    let finishSave: ((value: ActionGuideResource) => void) | undefined
    vi.mocked(getActionGuide).mockResolvedValue({ ...resource, status: 'draft', guide })
    vi.mocked(saveActionGuide).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishSave = resolve
        }),
    )
    show({ onDirtyChange })
    const select = await screen.findByRole('combobox', { name: '신청할 수 있는 사람 안내 상태' })
    await user.selectOptions(select, 'needs_review')
    await user.click(screen.getByRole('button', { name: '안내문 저장' }))
    expect(select).toBeDisabled()
    fireEvent.change(select, { target: { value: 'available' } })
    expect(onDirtyChange.mock.calls.filter(([value]) => value === true)).toHaveLength(1)
    await act(async () => {
      finishSave?.({ ...resource, status: 'draft', guide: { ...guide, guide_revision: 2 } })
    })
    expect(screen.getByRole('button', { name: '안내문 저장' })).toBeDisabled()
  })

  it('접수 응답이 불확실하면 같은 request_id를 재확인 후 재전송한다', async () => {
    const user = userEvent.setup()
    vi.mocked(createActionGuideJob)
      .mockRejectedValueOnce(new ApiError(0, '네트워크'))
      .mockResolvedValueOnce({ job: { ...candidate, status: 'queued' }, creditBalance: 8 })
    show()
    await user.click(await screen.findByRole('button', { name: '안내문 만들기' }))
    await user.click(screen.getByRole('button', { name: '2크레딧으로 안내문 만들기' }))
    await user.click(await screen.findByRole('button', { name: '작업 상태 다시 확인' }))
    expect(createActionGuideJob).toHaveBeenCalledTimes(1)
    await user.click(await screen.findByRole('button', { name: '같은 요청 다시 보내기' }))
    await waitFor(() => expect(createActionGuideJob).toHaveBeenCalledTimes(2))
    const first = vi.mocked(createActionGuideJob).mock.calls.at(0)?.[1]
    expect(first?.request_id).toBe('generated-uuid')
    expect(vi.mocked(createActionGuideJob).mock.calls.at(1)?.[1]).toEqual(first)
  })
})
