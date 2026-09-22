import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, getExplanations } from '../api/client'
import type { Explanation } from '../api/types'
import { ExplanationsPanel } from './ExplanationsPanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getExplanations: vi.fn(),
}))

function explanation(overrides: Partial<Explanation> = {}): Explanation {
  return {
    term: '서류',
    definition_source: 'dictionary_reviewed',
    explanation: '신청할 때 기관에 내는 종이 문서입니다.',
    source_anchors: [{ source_unit_indexes: [0], quote: '서류' }],
    ...overrides,
  }
}

beforeEach(() => {
  vi.mocked(getExplanations).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ExplanationsPanel', () => {
  it('로딩 뒤 용어 목록을 표시한다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    expect(screen.getByRole('status')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('서류')).toBeInTheDocument())
    expect(getExplanations).toHaveBeenCalledWith('c1', expect.any(AbortSignal))
  })

  it('기본은 접힌 상태이고 펼치면 접근성 이름과 aria 속성이 함께 바뀐다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    const user = userEvent.setup()
    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    const toggle = await screen.findByRole('button', { name: '설명 더 보기' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).not.toHaveAttribute('aria-controls')
    expect(screen.queryByText('신청할 때 기관에 내는 종이 문서입니다.')).not.toBeInTheDocument()

    await user.click(toggle)

    const collapseButton = screen.getByRole('button', { name: '설명 접기' })
    expect(collapseButton).toHaveAttribute('aria-expanded', 'true')
    expect(collapseButton).toHaveAttribute('aria-controls')
    expect(screen.getByText('신청할 때 기관에 내는 종이 문서입니다.')).toBeInTheDocument()
  })

  it('키보드 Enter/Space로도 펼치고 접을 수 있다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    const user = userEvent.setup()
    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    const toggle = await screen.findByRole('button', { name: '설명 더 보기' })
    toggle.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: '설명 접기' })).toBeInTheDocument()

    await user.keyboard(' ')
    expect(screen.getByRole('button', { name: '설명 더 보기' })).toBeInTheDocument()
  })

  it('목록이 비어 있으면 안내문만 보이고 펼침 버튼은 어디에도 없다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [],
    })

    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await waitFor(() =>
      expect(screen.getByText('이 본문에서 설명을 제공할 용어가 없습니다.')).toBeInTheDocument(),
    )
    expect(screen.queryByRole('button', { name: /설명/ })).not.toBeInTheDocument()
  })

  it('원문 위치가 있으면 원문 보기가 평탄화된 인덱스로 onNavigateSource를 호출한다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [
        explanation({
          source_anchors: [
            { source_unit_indexes: [0, 1], quote: '서류' },
            { source_unit_indexes: [4], quote: '서류' },
          ],
        }),
      ],
    })

    const onNavigateSource = vi.fn()
    const user = userEvent.setup()
    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={onNavigateSource}
      />,
    )

    await user.click(await screen.findByRole('button', { name: '설명 더 보기' }))
    await user.click(screen.getByRole('button', { name: '원문 보기' }))

    expect(onNavigateSource).toHaveBeenCalledWith([0, 1, 4], expect.any(HTMLButtonElement))
  })

  it('원문 위치가 없으면 원문 보기 대신 안내문을 보여준다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation({ source_anchors: [] })],
    })

    const user = userEvent.setup()
    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await user.click(await screen.findByRole('button', { name: '설명 더 보기' }))

    expect(screen.getByText('원문 위치를 찾지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '원문 보기' })).not.toBeInTheDocument()
  })

  it('원문 패널이 준비되지 않았으면 원문 위치가 있어도 원문 보기를 감춘다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    const user = userEvent.setup()
    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable={false}
        onNavigateSource={vi.fn()}
      />,
    )

    await user.click(await screen.findByRole('button', { name: '설명 더 보기' }))

    expect(screen.getByText('원문 위치를 찾지 못했습니다.')).toBeInTheDocument()
  })

  it('수정 중이면 저장 후 다시 확인하라는 안내문을 보여준다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await waitFor(() =>
      expect(
        screen.getByText('수정 중인 글입니다. 본문을 저장한 뒤 다시 확인해 주세요.'),
      ).toBeInTheDocument(),
    )
  })

  it('본문 버전(contentRevision)이 바뀌면 다시 불러온다', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    const { rerender } = render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await waitFor(() => expect(getExplanations).toHaveBeenCalledTimes(1))

    rerender(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={2}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await waitFor(() => expect(getExplanations).toHaveBeenCalledTimes(2))
  })

  it('수정 중(dirty)이면 현재 본문과 다름 표시가 나타나고, 저장되면 사라진다(AC-R6)', async () => {
    vi.mocked(getExplanations).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 1,
      explanations: [explanation()],
    })

    const { rerender } = render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await waitFor(() => expect(screen.getByText('현재 본문과 다름')).toBeInTheDocument())
    // 비활성이 아니라 표시만 하는 것이므로 펼침 버튼은 그대로 눌린다.
    expect(screen.getByRole('button', { name: '설명 더 보기' })).toBeEnabled()

    rerender(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    await waitFor(() => expect(screen.queryByText('현재 본문과 다름')).not.toBeInTheDocument())
  })

  it('불러오기 실패는 ApiError 메시지를 alert로 보여준다', async () => {
    vi.mocked(getExplanations).mockRejectedValue(new ApiError(500, '용어 설명을 읽을 수 없습니다.'))

    render(
      <ExplanationsPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        sourceAvailable
        onNavigateSource={vi.fn()}
      />,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('용어 설명을 읽을 수 없습니다.')
  })
})
