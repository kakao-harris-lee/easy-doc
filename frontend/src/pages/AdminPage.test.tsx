import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  listAdminAnnouncements,
  listAdminInvoiceRequests,
  listAdminWorkspaces,
  readAdminErrors,
} from '../api/admin'
import { adminWorkspaceListResponse } from '../test/factories'
import { AdminPage } from './AdminPage'

vi.mock('../api/admin', () => ({
  listAdminWorkspaces: vi.fn(),
  readAdminWorkspace: vi.fn(),
  adjustAdminWorkspaceCredits: vi.fn(),
  listAdminInvoiceRequests: vi.fn(),
  handleAdminInvoiceRequest: vi.fn(),
  readAdminErrors: vi.fn(),
  listAdminAnnouncements: vi.fn(),
  createAdminAnnouncement: vi.fn(),
  updateAdminAnnouncement: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(listAdminWorkspaces).mockResolvedValue(adminWorkspaceListResponse())
  vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
    items: [],
    page: 1,
    size: 20,
    total: 0,
  })
  vi.mocked(readAdminErrors).mockResolvedValue({ counts: [], recent: [] })
  vi.mocked(listAdminAnnouncements).mockResolvedValue({ items: [] })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('AdminPage — 탭 (어드민 최소, 계약 2.25.0)', () => {
  it('탭 4개를 role=tablist로 보여주고 첫 탭(워크스페이스)이 선택돼 있다', async () => {
    render(<AdminPage />)

    const tabs = await screen.findAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      '워크스페이스',
      '세금계산서',
      '오류',
      '공지',
    ])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(vi.mocked(listAdminWorkspaces)).toHaveBeenCalled()
  })

  it('탭을 누르면 그 탭의 패널이 열리고 데이터를 조회한다', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findAllByRole('tab')

    await user.click(screen.getByRole('tab', { name: '세금계산서' }))

    expect(screen.getByRole('tab', { name: '세금계산서' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(vi.mocked(listAdminInvoiceRequests)).toHaveBeenCalled())
  })

  it('화살표 오른쪽으로 다음 탭에 초점이 가고 선택도 옮겨간다', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    const [first] = await screen.findAllByRole('tab')
    first?.focus()

    await user.keyboard('{ArrowRight}')

    const invoicesTab = screen.getByRole('tab', { name: '세금계산서' })
    expect(invoicesTab).toHaveFocus()
    expect(invoicesTab).toHaveAttribute('aria-selected', 'true')
  })

  it('End를 누르면 마지막 탭(공지)으로 이동한다', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    const [first] = await screen.findAllByRole('tab')
    first?.focus()

    await user.keyboard('{End}')

    const announcementsTab = screen.getByRole('tab', { name: '공지' })
    expect(announcementsTab).toHaveFocus()
    expect(announcementsTab).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(vi.mocked(listAdminAnnouncements)).toHaveBeenCalled())
  })

  it('다른 탭에 갔다 돌아와도 이전 탭의 검색어가 그대로 남는다', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findAllByRole('tab')

    const search = screen.getByLabelText('이름·소유자 이메일 검색')
    await user.type(search, '복지')
    expect(search).toHaveValue('복지')

    // 다른 탭으로 갔다가 돌아온다 — 패널은 계속 마운트돼 있으므로(`hidden`으로만
    // 감춘다) 워크스페이스 탭이 처음부터 다시 그려지지 않는다.
    await user.click(screen.getByRole('tab', { name: '세금계산서' }))
    await user.click(screen.getByRole('tab', { name: '워크스페이스' }))

    expect(screen.getByLabelText('이름·소유자 이메일 검색')).toHaveValue('복지')
  })
})
