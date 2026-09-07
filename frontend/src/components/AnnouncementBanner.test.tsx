import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listActiveAnnouncements } from '../api/announcements'
import { AuthContext } from '../auth/context'
import { authContextValue, activeAnnouncement, workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { AnnouncementBanner } from './AnnouncementBanner'
import { AppLayout } from './AppLayout'

vi.mock('../api/announcements', () => ({
  listActiveAnnouncements: vi.fn(),
}))

function renderBanner(status: 'authenticated' | 'anonymous' | 'loading' = 'authenticated') {
  return render(
    <AuthContext.Provider value={authContextValue({ status })}>
      <AnnouncementBanner />
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  window.localStorage.clear()
  vi.mocked(listActiveAnnouncements).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('공지 배너 (어드민 최소, 계약 2.25.0)', () => {
  it('활성 공지를 role=region 안에 보여준다', async () => {
    vi.mocked(listActiveAnnouncements).mockResolvedValue({
      items: [activeAnnouncement({ id: 'a1', body: '9월 정기 점검 안내입니다.' })],
    })

    renderBanner()

    const region = await screen.findByRole('region', { name: '공지' })
    expect(region).toHaveTextContent('9월 정기 점검 안내입니다.')
  })

  it('공지가 없으면 아무것도 그리지 않는다', async () => {
    vi.mocked(listActiveAnnouncements).mockResolvedValue({ items: [] })

    renderBanner()

    // 조회가 끝날 때까지 기다린 뒤에도 region이 나타나지 않아야 한다.
    await vi.waitFor(() => expect(vi.mocked(listActiveAnnouncements)).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: '공지' })).not.toBeInTheDocument()
  })

  it('인증 전에는 조회하지 않는다', () => {
    renderBanner('anonymous')

    expect(vi.mocked(listActiveAnnouncements)).not.toHaveBeenCalled()
  })

  it('닫기를 누르면 그 공지가 사라지고 localStorage에 남는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listActiveAnnouncements).mockResolvedValue({
      items: [activeAnnouncement({ id: 'a1', body: '9월 정기 점검 안내입니다.' })],
    })

    renderBanner()

    await screen.findByRole('region', { name: '공지' })
    await user.click(screen.getByRole('button', { name: /닫기/ }))

    expect(screen.queryByRole('region', { name: '공지' })).not.toBeInTheDocument()
    const stored = JSON.parse(
      window.localStorage.getItem('easydoc.dismissed_announcement_ids') ?? '[]',
    )
    expect(stored).toEqual(['a1'])
  })

  it('닫은 공지는 새로고침(다시 마운트)해도 다시 뜨지 않는다', async () => {
    window.localStorage.setItem('easydoc.dismissed_announcement_ids', JSON.stringify(['a1']))
    vi.mocked(listActiveAnnouncements).mockResolvedValue({
      items: [activeAnnouncement({ id: 'a1', body: '9월 정기 점검 안내입니다.' })],
    })

    renderBanner()

    await vi.waitFor(() => expect(vi.mocked(listActiveAnnouncements)).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: '공지' })).not.toBeInTheDocument()
  })

  it('닫히지 않은 공지와 함께 있으면 그 공지만 남는다(최대 5건 중 일부만 닫힌 경우)', async () => {
    const user = userEvent.setup()
    vi.mocked(listActiveAnnouncements).mockResolvedValue({
      items: [
        activeAnnouncement({ id: 'a1', body: '첫 번째 공지' }),
        activeAnnouncement({ id: 'a2', body: '두 번째 공지' }),
      ],
    })

    renderBanner()

    await screen.findByText('첫 번째 공지')
    expect(screen.getByText('두 번째 공지')).toBeInTheDocument()

    const closeButtons = screen.getAllByRole('button', { name: /닫기/ })
    await user.click(closeButtons[0]!)

    expect(screen.queryByText('첫 번째 공지')).not.toBeInTheDocument()
    expect(screen.getByText('두 번째 공지')).toBeInTheDocument()
  })

  it('화면에서도 최대 5건까지만 보여준다(계약이 이미 5건으로 자르지만 다시 지킨다)', async () => {
    const items = Array.from({ length: 7 }, (_, index) =>
      activeAnnouncement({ id: `a${index}`, body: `공지 ${index}` }),
    )
    vi.mocked(listActiveAnnouncements).mockResolvedValue({ items })

    renderBanner()

    await screen.findByText('공지 0')
    expect(screen.getAllByRole('button', { name: /닫기/ })).toHaveLength(5)
    expect(screen.queryByText('공지 5')).not.toBeInTheDocument()
  })

  it('닫기 버튼의 접근 가능한 이름에 공지 본문 일부가 실린다', async () => {
    vi.mocked(listActiveAnnouncements).mockResolvedValue({
      items: [activeAnnouncement({ id: 'a1', body: '9월 정기 점검 안내입니다.' })],
    })

    renderBanner()

    expect(
      await screen.findByRole('button', { name: '닫기 — 9월 정기 점검 안내입니다.' }),
    ).toBeInTheDocument()
  })

  it('AppLayout에 마운트된 채로 라우트를 두 번 오가도 조회는 한 번만 나간다', async () => {
    vi.mocked(listActiveAnnouncements).mockResolvedValue({ items: [] })
    const user = userEvent.setup()

    render(
      <AuthContext.Provider value={authContextValue({ status: 'authenticated' })}>
        <WorkspaceContext.Provider value={workspaceContext({ workspaces: [], currentId: null })}>
          <MemoryRouter initialEntries={['/a']}>
            <AppLayout>
              <Routes>
                <Route path="/a" element={<Link to="/b">다음 화면</Link>} />
                <Route path="/b" element={<Link to="/a">이전 화면</Link>} />
              </Routes>
            </AppLayout>
          </MemoryRouter>
        </WorkspaceContext.Provider>
      </AuthContext.Provider>,
    )

    await vi.waitFor(() => expect(vi.mocked(listActiveAnnouncements)).toHaveBeenCalledTimes(1))

    // AppLayout이 <Routes>를 감싸고 있어 라우트가 바뀌어도 AnnouncementBanner는 다시
    // 마운트되지 않는다 — 그래서 조회도 다시 나가지 않는다.
    await user.click(screen.getByRole('link', { name: '다음 화면' }))
    await user.click(screen.getByRole('link', { name: '이전 화면' }))

    expect(vi.mocked(listActiveAnnouncements)).toHaveBeenCalledTimes(1)
  })
})
