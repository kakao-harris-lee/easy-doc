import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, deleteDocument, listDocuments } from '../api/client'
import type { DocumentListItem } from '../api/types'
import { documentItem, workspaceContext, workspaceItem } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import type { WorkspaceContextValue } from '../workspace/context'
import { HistoryPage } from './HistoryPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  listDocuments: vi.fn(),
  deleteDocument: vi.fn(),
}))

function page(workspace: Partial<WorkspaceContextValue> = {}) {
  return (
    <WorkspaceContext.Provider value={workspaceContext(workspace)}>
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    </WorkspaceContext.Provider>
  )
}

function renderPage(workspace: Partial<WorkspaceContextValue> = {}) {
  return render(page(workspace))
}

/**
 * 화면 폭을 고정한다.
 *
 * jsdom에는 matchMedia가 없어서 화면은 기본값(표)으로 그려진다. 카드 목록을 보려면
 * "좁은 화면"이라고 답하는 matchMedia를 꽂아야 한다.
 */
function stubViewport(tableView: boolean): void {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: tableView,
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
  vi.mocked(listDocuments).mockReset()
  vi.mocked(deleteDocument).mockReset()
  vi.restoreAllMocks()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('지금 해야 할 일', () => {
  /**
   * 서버 상태 조합 → 사용자가 읽을 말(DESIGN.md §6.6).
   *
   * 표로 고정하는 이유: `done`인데 `reviewed_at`이 비어 있는 줄과 채워진 줄은 서버에서
   * 한 글자 차이지만 사용자에게는 "지금 내 차례"와 "끝난 일"로 정반대다. 그리고 두 번째
   * 열은 `pending`·`processing` 같은 처리 상태가 아니라 할 일로 읽혀야 하므로, 규칙이
   * 아니라 **화면에 나온 말**로 고정한다.
   */
  const cases: [string, Partial<DocumentListItem>, string][] = [
    ['대기 중이면 변환 중', { status: 'pending' }, '변환 중'],
    ['처리 중이면 변환 중', { status: 'processing' }, '변환 중'],
    ['완료했지만 검수 전이면 검수 필요', { status: 'done', reviewed_at: null }, '검수 필요'],
    [
      '검수 시각이 있으면 검수함',
      { status: 'done', reviewed_at: '2026-08-07T02:00:00Z' },
      '검수함',
    ],
    ['실패는 실패', { status: 'failed' }, '실패'],
    // 변환 행이 없는 문서다. 백엔드가 최신 변환을 LEFT JOIN으로 붙이므로 status와
    // conversion_id가 함께 빈다. 진행 중인 일이 없어서 '변환 중'이라 하면 기다리면
    // 끝난다는 거짓말이 되고, 실패한 것도 아니다.
    ['변환 행이 없으면 변환 없음', { status: null, conversion_id: null }, '변환 없음'],
  ]

  it.each(cases)('%s', async (_name, patch, expected) => {
    vi.mocked(listDocuments).mockResolvedValue({
      items: [documentItem(patch)],
      limit: 20,
      offset: 0,
      has_more: false,
    })
    renderPage()

    expect(await screen.findByRole('row', { name: /재난지원금 안내/ })).toHaveTextContent(expected)
  })
})

describe('변환 기록', () => {
  it('문서 한 줄에 할 일·보조 정보·글자 수를 보여준다', async () => {
    vi.mocked(listDocuments).mockResolvedValue({
      items: [
        documentItem({ id: 'd1', title: '재난지원금 안내', char_count: 1200 }),
        documentItem({
          id: 'd2',
          conversion_id: 'c2',
          title: '검수한 문서',
          status: 'done',
          reviewed_at: '2026-08-07T02:00:00Z',
        }),
      ],
      limit: 20,
      offset: 0,
      has_more: false,
    })
    renderPage()

    const draftRow = await screen.findByRole('row', { name: /재난지원금 안내/ })
    // 두 번째 열은 처리 상태가 아니라 지금 할 일이다(§6.6).
    expect(draftRow).toHaveTextContent('검수 필요')
    expect(draftRow).toHaveTextContent('1,200자')
    // 제목 아래 보조 정보는 원본 형식과 올린 날짜다.
    expect(draftRow).toHaveTextContent('붙여넣기 · 2026. 8. 7.')
    expect(screen.getByRole('row', { name: /검수한 문서/ })).toHaveTextContent('검수함')
    // 제목이 검수 화면으로 가는 통로다.
    expect(screen.getByRole('link', { name: '재난지원금 안내' })).toHaveAttribute(
      'href',
      '/conversions/c1',
    )
  })

  it('더 보기를 누르면 다음 쪽을 이어 붙인다', async () => {
    const user = userEvent.setup()
    vi.mocked(listDocuments)
      .mockResolvedValueOnce({
        items: [documentItem({ id: 'd1', title: '첫 쪽 문서' })],
        limit: 20,
        offset: 0,
        has_more: true,
      })
      .mockResolvedValueOnce({
        items: [documentItem({ id: 'd2', conversion_id: 'c2', title: '둘째 쪽 문서' })],
        limit: 20,
        offset: 1,
        has_more: false,
      })
    renderPage()

    await user.click(await screen.findByRole('button', { name: '더 보기' }))

    expect(await screen.findByText('둘째 쪽 문서')).toBeInTheDocument()
    // 이미 본 줄은 사라지지 않는다.
    expect(screen.getByText('첫 쪽 문서')).toBeInTheDocument()
    expect(vi.mocked(listDocuments).mock.calls[1]?.[0]).toEqual({
      limit: 20,
      offset: 1,
      workspaceId: 'w1',
    })
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('한 건도 없으면 지금 작업 공간 이름과 첫 변환 행동을 보여준다', async () => {
    vi.mocked(listDocuments).mockResolvedValue({
      items: [],
      limit: 20,
      offset: 0,
      has_more: false,
    })
    renderPage({ workspaces: [workspaceItem({ id: 'w1', name: '민원 안내' })], currentId: 'w1' })

    // 빈 상태도 "어디가 비었는지"를 말한다 — 작업 공간을 잘못 고른 것과 정말 문서가
    // 없는 것은 사용자에게 전혀 다른 상황이다(DESIGN.md §6.6).
    expect(
      await screen.findByText('‘민원 안내’에는 아직 변환한 문서가 없습니다.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '첫 문서 변환하기' })).toHaveAttribute('href', '/')
  })

  it('불러오지 못하면 사유를 알린다', async () => {
    vi.mocked(listDocuments).mockRejectedValue(new Error('network down'))
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('변환 기록을 불러오지 못했습니다')
  })
})

describe('좁은 화면', () => {
  it('표 대신 카드 목록으로 바꾸고 표를 남기지 않는다', async () => {
    stubViewport(false)
    vi.mocked(listDocuments).mockResolvedValue({
      items: [documentItem({ id: 'd1', title: '재난지원금 안내' })],
      limit: 20,
      offset: 0,
      has_more: false,
    })
    renderPage()

    const list = await screen.findByRole('list', { name: /변환한 문서 목록입니다/ })
    // 카드 안에서도 §6.6의 위계를 지킨다: 제목 → 할 일 → 보조 정보 → 삭제.
    const card = within(list).getByRole('listitem')
    expect(card).toHaveTextContent('검수 필요')
    expect(card).toHaveTextContent('붙여넣기 · 2026. 8. 7.')
    expect(within(card).getByRole('link', { name: '재난지원금 안내' })).toHaveAttribute(
      'href',
      '/conversions/c1',
    )

    // 왜 이것을 재는가: 표와 카드를 둘 다 그려 두고 CSS로 한쪽만 감추는 흔한 해법은
    // 낭독기에게 같은 목록을 두 번 들려준다. 감춰진 표도 접근성 트리에 남기 때문이다.
    // queryByRole은 보이는 것만 세므로, DOM에 정말 한 벌만 있는지는 element로 확인한다.
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(document.querySelector('table')).toBeNull()
    expect(screen.getAllByText('재난지원금 안내')).toHaveLength(1)
  })
})

describe('작업 공간 필터', () => {
  it('지금 고른 작업 공간의 문서만 조회한다', async () => {
    vi.mocked(listDocuments).mockResolvedValue({
      items: [documentItem({ id: 'd1', title: '민원 문서' })],
      limit: 20,
      offset: 0,
      has_more: false,
    })
    renderPage({
      workspaces: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
      currentId: 'w2',
    })

    await screen.findByText('민원 문서')

    expect(vi.mocked(listDocuments).mock.calls[0]?.[0]).toEqual({
      limit: 20,
      offset: 0,
      workspaceId: 'w2',
    })
    // 어느 작업 공간을 보고 있는지 표 설명으로도 알린다(화면을 보지 않는 사용자).
    expect(screen.getByRole('table')).toHaveAccessibleName(/민원 안내/)
  })

  it('작업 공간을 바꾸면 첫 쪽부터 다시 읽는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listDocuments)
      .mockResolvedValueOnce({
        items: [documentItem({ id: 'd1', title: '첫 쪽 문서' })],
        limit: 20,
        offset: 0,
        has_more: true,
      })
      .mockResolvedValueOnce({
        items: [documentItem({ id: 'd2', conversion_id: 'c2', title: '둘째 쪽 문서' })],
        limit: 20,
        offset: 1,
        has_more: false,
      })
      .mockResolvedValue({
        items: [documentItem({ id: 'd3', conversion_id: 'c3', title: '민원 문서' })],
        limit: 20,
        offset: 0,
        has_more: false,
      })
    const workspaces = [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })]
    const { rerender } = renderPage({ workspaces, currentId: 'w1' })
    await user.click(await screen.findByRole('button', { name: '더 보기' }))
    await screen.findByText('둘째 쪽 문서')

    rerender(page({ workspaces, currentId: 'w2' }))

    expect(await screen.findByText('민원 문서')).toBeInTheDocument()
    // 이어 붙여 둔 이전 작업 공간의 줄은 남지 않는다.
    expect(screen.queryByText('첫 쪽 문서')).not.toBeInTheDocument()
    expect(screen.queryByText('둘째 쪽 문서')).not.toBeInTheDocument()
    // 바뀐 작업 공간을 예전 offset으로 읽으면 이미 지난 쪽부터 보게 된다.
    expect(vi.mocked(listDocuments).mock.calls[2]?.[0]).toEqual({
      limit: 20,
      offset: 0,
      workspaceId: 'w2',
    })
  })

  it('작업 공간을 아직 못 받았으면 거르지 않는다', async () => {
    vi.mocked(listDocuments).mockResolvedValue({
      items: [documentItem({ id: 'd1' })],
      limit: 20,
      offset: 0,
      has_more: false,
    })
    renderPage({ workspaces: [], currentId: null })

    await screen.findByText('재난지원금 안내')

    expect(vi.mocked(listDocuments).mock.calls[0]?.[0]).toEqual({ limit: 20, offset: 0 })
  })
})

describe('문서 삭제', () => {
  /** 문서 한 건만 있는 첫 쪽. 삭제 뒤에는 빈 목록을 돌려준다. */
  function mockOneThenEmpty() {
    vi.mocked(listDocuments)
      .mockResolvedValueOnce({
        items: [documentItem({ id: 'd1', title: '재난지원금 안내' })],
        limit: 20,
        offset: 0,
        has_more: false,
      })
      .mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
  }

  it('묻고 나서 지운 뒤 목록을 다시 읽는다', async () => {
    const user = userEvent.setup()
    mockOneThenEmpty()
    vi.mocked(deleteDocument).mockResolvedValue(undefined)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    await user.click(await screen.findByRole('button', { name: '재난지원금 안내 삭제' }))

    // 무엇이 사라지는지 대화상자 안에서 확인할 수 있어야 한다(§9) — 줄마다 같은 문장이
    // 뜨면 다른 문서의 삭제 버튼을 눌렀는지 알아챌 방법이 없다.
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('‘재난지원금 안내’'))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('되돌릴 수 없습니다'))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('즉시 삭제됩니다'))
    expect(vi.mocked(deleteDocument)).toHaveBeenCalledWith('d1')
    // 지운 줄이 사라지고 첫 쪽부터 다시 읽는다 — 삭제로 다음 쪽 경계가 밀리기 때문이다.
    expect(await screen.findByText(/아직 변환한 문서가 없습니다/)).toBeInTheDocument()
    expect(vi.mocked(listDocuments).mock.calls[1]?.[0]).toEqual({
      limit: 20,
      offset: 0,
      workspaceId: 'w1',
    })
  })

  it('취소하면 아무것도 지우지 않는다', async () => {
    const user = userEvent.setup()
    mockOneThenEmpty()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    await user.click(await screen.findByRole('button', { name: '재난지원금 안내 삭제' }))

    expect(vi.mocked(deleteDocument)).not.toHaveBeenCalled()
    expect(screen.getByText('재난지원금 안내')).toBeInTheDocument()
    // 다시 읽지도 않는다 — 첫 조회 한 번뿐이다.
    expect(vi.mocked(listDocuments)).toHaveBeenCalledTimes(1)
  })

  it('지우지 못하면 사유를 알리고 줄을 남겨 둔다', async () => {
    const user = userEvent.setup()
    mockOneThenEmpty()
    vi.mocked(deleteDocument).mockRejectedValue(new ApiError(404, '문서를 찾을 수 없습니다'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    await user.click(await screen.findByRole('button', { name: '재난지원금 안내 삭제' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('문서를 찾을 수 없습니다')
    expect(screen.getByText('재난지원금 안내')).toBeInTheDocument()
  })
})
