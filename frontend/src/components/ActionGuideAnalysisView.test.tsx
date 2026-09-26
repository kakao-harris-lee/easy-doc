import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import type { ActionGuideAnalysis, GuideInformation } from '../api/types'
import { ActionGuideAnalysisView } from './ActionGuideAnalysisView'

const source = '관람을 원하면 일주일 전에 문의하세요. 주말에는 문의를 받지 않습니다.'
const evidence = [{ source_unit_indexes: [1], quote: source }]
const present = (text: string): GuideInformation => ({ status: 'present', text, evidence })
const missing: GuideInformation = { status: 'not_in_source', text: null, evidence: [] }
const analysis: ActionGuideAnalysis = {
  schema_version: 2,
  analysis_id: 'analysis-1',
  analysis_revision: 1,
  based_on_content_revision: 1,
  state: 'current',
  provenance: 'fake',
  reading_level: 'grade_5_6',
  suitability: 'mixed',
  action_presence: 'found',
  reason: '사업 소개와 문의 안내가 함께 있습니다.',
  evidence,
  source_units: [
    { id: 0, text: '누구나 문화를 즐기도록 돕는 사업입니다.' },
    { id: 1, text: source },
  ],
  actions: [
    {
      id: 'inquiry',
      instruction: present('관람을 원하면 문의하세요.'),
      actor: present('관람을 원하는 사람'),
      beneficiaries: { status: 'needs_review', text: '사업 대상 확인', evidence: [] },
      conditions: [present('주말에는 문의를 받지 않습니다.')],
      deadline: present('일주일 전에 문의하세요.'),
      preparation: { status: 'not_applicable', text: null, evidence: [] },
      contact: missing,
      after_action_ids: [],
      order_evidence: [],
    },
  ],
  coverage: [
    { source_unit_id: 0, status: 'context', action_ids: [] },
    { source_unit_id: 1, status: 'action', action_ids: ['inquiry'] },
  ],
  unresolved_signals: [],
  extraction_review_complete: false,
  allowed_modes: [],
  generation_enabled: false,
  created_at: '2026-09-26T00:00:00Z',
}

describe('행동 분석 읽기', () => {
  it('행동 하나의 조건과 기한을 숨기지 않고 미기재와 해당 없음을 구분한다', async () => {
    const user = userEvent.setup()
    render(<ActionGuideAnalysisView analysis={analysis} />)
    const actions = screen.getByRole('region', { name: '원문에서 찾은 행동' })
    expect(within(actions).getAllByRole('listitem')).toHaveLength(1)
    expect(within(actions).getByText('주말에는 문의를 받지 않습니다.')).toBeVisible()
    expect(within(actions).getByText('일주일 전에 문의하세요.')).toBeVisible()
    expect(within(actions).getByText('원문에 안내 없음')).toBeVisible()
    expect(within(actions).getByText('해당 없음')).toBeVisible()
    expect(within(actions).getByText('사업 대상 확인')).toBeVisible()
    expect(within(actions).getByText('원문 근거 확인 필요')).toBeVisible()
    expect(within(actions).queryByText('원문에서 먼저 하도록 안내한 일')).not.toBeInTheDocument()
    const firstEvidence = within(actions).getAllByText('원문 근거 보기')[0]
    if (!firstEvidence) throw new Error('근거 열기 없음')
    await user.click(firstEvidence)
    expect(within(actions).getAllByText(`원문 부분 2: ${source}`)[0]).toBeVisible()
  })

  it('배경도 전체 대조에 남기고 대응이 의미 보존 완료를 뜻하지 않음을 알린다', () => {
    render(<ActionGuideAnalysisView analysis={analysis} />)
    const comparison = screen.getByRole('region', { name: '원문 전체 대조' })
    expect(within(comparison).getByText('누구나 문화를 즐기도록 돕는 사업입니다.')).toBeVisible()
    expect(within(comparison).getByText(source)).toBeVisible()
    expect(within(comparison).getByText(/뜻이 모두 보존되었다는 의미는 아닙니다/)).toBeVisible()
  })

  it.each(['none', 'uncertain'] as const)(
    '할 일 %s 상태에 행동 절차를 만들어 표시하지 않는다',
    (presence) => {
      render(
        <ActionGuideAnalysisView
          analysis={{ ...analysis, actions: [], action_presence: presence }}
        />,
      )
      expect(screen.queryByRole('region', { name: '원문에서 찾은 행동' })).not.toBeInTheDocument()
      expect(
        screen.getByText(
          presence === 'none'
            ? '원문에서 할 일을 찾지 못했습니다.'
            : '원문에서 할 일을 더 확인해야 합니다.',
        ),
      ).toBeVisible()
    },
  )
})
