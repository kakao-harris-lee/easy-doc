import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { handleAdminInvoiceRequest, listAdminInvoiceRequests } from '../../api/admin'
import { ApiError } from '../../api/client'
import { invoiceRequest } from '../../test/factories'
import { AdminInvoicesTab } from './AdminInvoicesTab'

vi.mock('../../api/admin', () => ({
  listAdminInvoiceRequests: vi.fn(),
  handleAdminInvoiceRequest: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(listAdminInvoiceRequests).mockReset()
  vi.mocked(handleAdminInvoiceRequest).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('AdminInvoicesTab — 세금계산서 (어드민 최소, 계약 2.25.0)', () => {
  it('기본은 requested 필터로 조회한다', async () => {
    vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
      items: [invoiceRequest({ company_name: '쉬운글 주식회사' })],
      page: 1,
      size: 20,
      total: 1,
    })

    render(<AdminInvoicesTab />)

    expect(await screen.findByText('쉬운글 주식회사')).toBeInTheDocument()
    expect(vi.mocked(listAdminInvoiceRequests)).toHaveBeenCalledWith(
      { status: 'requested', page: 1, size: 20 },
      expect.anything(),
    )
  })

  it('상태 필터를 바꾸면 그 상태로 다시 부른다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
      items: [],
      page: 1,
      size: 20,
      total: 0,
    })

    render(<AdminInvoicesTab />)
    await waitFor(() => expect(vi.mocked(listAdminInvoiceRequests)).toHaveBeenCalledTimes(1))

    await user.selectOptions(screen.getByLabelText('상태'), 'issued')

    await waitFor(() =>
      expect(vi.mocked(listAdminInvoiceRequests)).toHaveBeenLastCalledWith(
        { status: 'issued', page: 1, size: 20 },
        expect.anything(),
      ),
    )
  })

  it('처리 폼으로 발급 처리하면 성공 문구를 보여주고 목록을 다시 읽는다', async () => {
    const user = userEvent.setup()
    const pending = invoiceRequest({
      id: 'inv1',
      company_name: '쉬운글 주식회사',
      status: 'requested',
    })
    vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
      items: [pending],
      page: 1,
      size: 20,
      total: 1,
    })
    vi.mocked(handleAdminInvoiceRequest).mockResolvedValue({
      ...pending,
      status: 'issued',
      handled_at: '2026-09-07T00:00:00Z',
    })

    render(<AdminInvoicesTab />)
    await screen.findByText('쉬운글 주식회사')

    const form = screen.getByRole('form', {
      name: '쉬운글 주식회사 요청 처리 (2026-08-01~2026-08-31)',
    })
    await user.selectOptions(form.querySelector('select') as HTMLSelectElement, 'issued')
    await user.click(screen.getByRole('button', { name: '처리하기' }))

    expect(await screen.findByText(/발급됨 처리했습니다/)).toBeInTheDocument()
    expect(vi.mocked(handleAdminInvoiceRequest)).toHaveBeenCalledWith('inv1', {
      status: 'issued',
      note: null,
    })
    await waitFor(() => expect(vi.mocked(listAdminInvoiceRequests)).toHaveBeenCalledTimes(2))
  })

  it('메모를 채워 처리하면 그 메모를 그대로 보낸다', async () => {
    const user = userEvent.setup()
    const pending = invoiceRequest({
      id: 'inv1',
      company_name: '쉬운글 주식회사',
      status: 'requested',
    })
    vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
      items: [pending],
      page: 1,
      size: 20,
      total: 1,
    })
    vi.mocked(handleAdminInvoiceRequest).mockResolvedValue({
      ...pending,
      status: 'rejected',
      handled_at: '2026-09-07T00:00:00Z',
    })

    render(<AdminInvoicesTab />)
    await screen.findByText('쉬운글 주식회사')

    const form = screen.getByRole('form', {
      name: '쉬운글 주식회사 요청 처리 (2026-08-01~2026-08-31)',
    })
    await user.selectOptions(form.querySelector('select') as HTMLSelectElement, 'rejected')
    await user.type(screen.getByLabelText('메모 (선택)'), '사업자등록번호 확인 불가')
    await user.click(screen.getByRole('button', { name: '처리하기' }))

    expect(await screen.findByText(/거절됨 처리했습니다/)).toBeInTheDocument()
    expect(vi.mocked(handleAdminInvoiceRequest)).toHaveBeenCalledWith('inv1', {
      status: 'rejected',
      note: '사업자등록번호 확인 불가',
    })
  })

  it('이미 처리된 요청에는 처리 폼을 그리지 않는다', async () => {
    vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
      items: [invoiceRequest({ id: 'inv1', company_name: '쉬운글 주식회사', status: 'issued' })],
      page: 1,
      size: 20,
      total: 1,
    })

    render(<AdminInvoicesTab />)
    await screen.findByText('쉬운글 주식회사')

    expect(
      screen.queryByRole('form', { name: '쉬운글 주식회사 요청 처리 (2026-08-01~2026-08-31)' }),
    ).not.toBeInTheDocument()
  })

  it('처리가 서버 오류로 실패하면 그 처리 폼에 서버 문구를 보여준다', async () => {
    const user = userEvent.setup()
    const pending = invoiceRequest({
      id: 'inv1',
      company_name: '쉬운글 주식회사',
      status: 'requested',
    })
    vi.mocked(listAdminInvoiceRequests).mockResolvedValue({
      items: [pending],
      page: 1,
      size: 20,
      total: 1,
    })
    vi.mocked(handleAdminInvoiceRequest).mockRejectedValue(
      new ApiError(409, '이미 처리된 요청입니다'),
    )

    render(<AdminInvoicesTab />)
    await screen.findByText('쉬운글 주식회사')
    await user.click(screen.getByRole('button', { name: '처리하기' }))

    expect(await screen.findByText('이미 처리된 요청입니다')).toBeInTheDocument()
  })

  it('목록 조회 실패는 서버 문구를 보여준다', async () => {
    vi.mocked(listAdminInvoiceRequests).mockRejectedValue(
      new ApiError(403, '관리자 권한이 필요합니다'),
    )

    render(<AdminInvoicesTab />)

    expect(await screen.findByText('관리자 권한이 필요합니다')).toBeInTheDocument()
  })
})
