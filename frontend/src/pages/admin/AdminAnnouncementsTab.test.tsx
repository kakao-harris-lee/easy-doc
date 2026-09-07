import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createAdminAnnouncement,
  listAdminAnnouncements,
  updateAdminAnnouncement,
} from '../../api/admin'
import { ApiError } from '../../api/client'
import { announcementResponse } from '../../test/factories'
import { AdminAnnouncementsTab } from './AdminAnnouncementsTab'

vi.mock('../../api/admin', () => ({
  listAdminAnnouncements: vi.fn(),
  createAdminAnnouncement: vi.fn(),
  updateAdminAnnouncement: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(listAdminAnnouncements).mockReset()
  vi.mocked(createAdminAnnouncement).mockReset()
  vi.mocked(updateAdminAnnouncement).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('AdminAnnouncementsTab — 공지 (어드민 최소, 계약 2.25.0)', () => {
  it('공지 목록을 보여준다', async () => {
    vi.mocked(listAdminAnnouncements).mockResolvedValue({
      items: [announcementResponse({ body: '9월 정기 점검 안내입니다.' })],
    })

    render(<AdminAnnouncementsTab />)

    expect(await screen.findByText('9월 정기 점검 안내입니다.')).toBeInTheDocument()
  })

  it('공지를 만들면 목록 맨 위에 얹는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminAnnouncements).mockResolvedValue({ items: [] })
    vi.mocked(createAdminAnnouncement).mockResolvedValue(
      announcementResponse({ id: 'a2', body: '새 공지입니다.' }),
    )

    render(<AdminAnnouncementsTab />)
    await waitFor(() => expect(vi.mocked(listAdminAnnouncements)).toHaveBeenCalled())

    await user.type(screen.getByLabelText('공지 내용'), '새 공지입니다.')
    await user.click(screen.getByRole('button', { name: '공지 만들기' }))

    expect(await screen.findByText('새 공지입니다.')).toBeInTheDocument()
    expect(vi.mocked(createAdminAnnouncement)).toHaveBeenCalledWith({ body: '새 공지입니다.' })
  })

  it('빈 내용으로 만들려 하면 서버를 부르지 않고 화면에서 막는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminAnnouncements).mockResolvedValue({ items: [] })

    render(<AdminAnnouncementsTab />)
    await waitFor(() => expect(vi.mocked(listAdminAnnouncements)).toHaveBeenCalled())

    await user.click(screen.getByRole('button', { name: '공지 만들기' }))

    expect(await screen.findByText('공지 내용을 입력해 주세요.')).toBeInTheDocument()
    expect(vi.mocked(createAdminAnnouncement)).not.toHaveBeenCalled()
  })

  it('활성 토글을 누르면 반대 값으로 PATCH하고 배지를 바꾼다', async () => {
    const user = userEvent.setup()
    const active = announcementResponse({ id: 'a1', body: '활성 공지', active: true })
    vi.mocked(listAdminAnnouncements).mockResolvedValue({ items: [active] })
    vi.mocked(updateAdminAnnouncement).mockResolvedValue({ ...active, active: false })

    render(<AdminAnnouncementsTab />)
    await screen.findByText('활성 공지')
    expect(screen.getByText('활성')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '비활성으로 전환' }))

    expect(vi.mocked(updateAdminAnnouncement)).toHaveBeenCalledWith('a1', { active: false })
    expect(await screen.findByText('비활성')).toBeInTheDocument()
  })

  it('고치기로 본문을 바꾸면 PATCH하고 새 본문을 보여준다', async () => {
    const user = userEvent.setup()
    const original = announcementResponse({ id: 'a1', body: '원래 내용' })
    vi.mocked(listAdminAnnouncements).mockResolvedValue({ items: [original] })
    vi.mocked(updateAdminAnnouncement).mockResolvedValue({ ...original, body: '고친 내용' })

    render(<AdminAnnouncementsTab />)
    await screen.findByText('원래 내용')

    await user.click(screen.getByRole('button', { name: '고치기' }))
    // 만들기 폼과 고치기 폼 둘 다 같은 라벨("공지 내용")을 쓴다 — 고치기 폼은 그 뒤에
    // 새로 나타난 쪽이다.
    const textareas = screen.getAllByLabelText('공지 내용')
    const textarea = textareas[textareas.length - 1] as HTMLTextAreaElement
    await user.clear(textarea)
    await user.type(textarea, '고친 내용')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(vi.mocked(updateAdminAnnouncement)).toHaveBeenCalledWith('a1', { body: '고친 내용' })
    expect(await screen.findByText('고친 내용')).toBeInTheDocument()
  })

  it('목록 조회가 실패하면 서버 문구를 보여준다', async () => {
    vi.mocked(listAdminAnnouncements).mockRejectedValue(
      new ApiError(403, '관리자 권한이 필요합니다'),
    )

    render(<AdminAnnouncementsTab />)

    expect(await screen.findByText('관리자 권한이 필요합니다')).toBeInTheDocument()
  })
})
