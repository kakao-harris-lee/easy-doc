import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, deleteDocument, listDocuments } from '../api/client'
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

beforeEach(() => {
  vi.mocked(listDocuments).mockReset()
  vi.mocked(deleteDocument).mockReset()
  vi.restoreAllMocks()
})

describe('변환 기록', () => {
  it('문서 한 줄에 상태·글자 수·검수 여부를 보여준다', async () => {
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
    expect(draftRow).toHaveTextContent('변환 완료')
    expect(draftRow).toHaveTextContent('1,200자')
    expect(draftRow).toHaveTextContent('초안')
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
