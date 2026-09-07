import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { readAdminErrors } from '../../api/admin'
import { ApiError } from '../../api/client'
import { adminErrorItem, adminErrorsResponse, adminFailureCount } from '../../test/factories'
import { AdminErrorsTab } from './AdminErrorsTab'

vi.mock('../../api/admin', () => ({
  readAdminErrors: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(readAdminErrors).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('AdminErrorsTab — 오류 (어드민 최소, 계약 2.25.0)', () => {
  it('이번 달 기본값으로 코드별 건수와 최근 목록을 보여준다', async () => {
    vi.mocked(readAdminErrors).mockResolvedValue(
      adminErrorsResponse({
        counts: [adminFailureCount({ failure_code: 'llm_error', count: 3 })],
        recent: [adminErrorItem({ id: 'c1', failure_code: 'llm_error' })],
      }),
    )

    render(<AdminErrorsTab />)

    // 코드별 건수 표와 최근 목록 표 둘 다 'llm_error'를 담는다.
    const occurrences = await screen.findAllByText('llm_error')
    expect(occurrences).toHaveLength(2)
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(vi.mocked(readAdminErrors)).toHaveBeenCalledWith({}, expect.anything())
  })

  it('직접 입력으로 바꾸고 두 날짜를 채우면 그 기간으로 조회한다', async () => {
    const user = userEvent.setup()
    vi.mocked(readAdminErrors).mockResolvedValue(adminErrorsResponse())

    render(<AdminErrorsTab />)
    await waitFor(() => expect(vi.mocked(readAdminErrors)).toHaveBeenCalledTimes(1))

    await user.click(screen.getByRole('radio', { name: '직접 입력' }))
    await user.type(screen.getByLabelText('시작일'), '2026-08-01')
    await user.type(screen.getByLabelText('종료일'), '2026-08-31')

    await waitFor(() =>
      expect(vi.mocked(readAdminErrors)).toHaveBeenLastCalledWith(
        { from: '2026-08-01', to: '2026-08-31' },
        expect.anything(),
      ),
    )
  })

  it('직접 입력으로 바꿨는데 날짜가 하나만 채워지면 빈 패널 대신 안내를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(readAdminErrors).mockResolvedValue(adminErrorsResponse())

    render(<AdminErrorsTab />)
    await waitFor(() => expect(vi.mocked(readAdminErrors)).toHaveBeenCalledTimes(1))

    await user.click(screen.getByRole('radio', { name: '직접 입력' }))
    await user.type(screen.getByLabelText('시작일'), '2026-08-01')

    expect(screen.getByText('시작일과 종료일을 모두 입력하면 조회합니다.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    // 두 날짜가 다 채워지기 전에는 서버를 다시 부르지 않는다.
    expect(vi.mocked(readAdminErrors)).toHaveBeenCalledTimes(1)
  })

  it('실패가 없으면 안내 문구를 보여준다', async () => {
    vi.mocked(readAdminErrors).mockResolvedValue(adminErrorsResponse({ counts: [], recent: [] }))

    render(<AdminErrorsTab />)

    expect(await screen.findAllByText('이 기간에 실패한 변환이 없습니다.')).toHaveLength(2)
  })

  it('조회가 실패하면 서버 문구를 보여준다', async () => {
    vi.mocked(readAdminErrors).mockRejectedValue(new ApiError(403, '관리자 권한이 필요합니다'))

    render(<AdminErrorsTab />)

    expect(await screen.findByText('관리자 권한이 필요합니다')).toBeInTheDocument()
  })
})
