import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { listDocuments } from '../api/client'
import { documentItem } from '../test/factories'
import { HistoryPage } from './HistoryPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  listDocuments: vi.fn(),
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <HistoryPage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.mocked(listDocuments).mockReset()
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
    expect(vi.mocked(listDocuments).mock.calls[1]?.[0]).toEqual({ limit: 20, offset: 1 })
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('불러오지 못하면 사유를 알린다', async () => {
    vi.mocked(listDocuments).mockRejectedValue(new Error('network down'))
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('변환 기록을 불러오지 못했습니다')
  })
})
