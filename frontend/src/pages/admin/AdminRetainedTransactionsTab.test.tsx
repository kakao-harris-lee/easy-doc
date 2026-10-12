import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getAdminRetainedTransaction,
  listAdminRetainedTransactions,
  postAdminComplaintDecision,
  postAdminRefundDecision,
} from '../../api/admin'
import { ApiError } from '../../api/client'
import { AdminRetainedTransactionsTab } from './AdminRetainedTransactionsTab'

vi.mock('../../api/admin', () => ({
  getAdminRetainedTransaction: vi.fn(),
  listAdminRetainedTransactions: vi.fn(),
  postAdminComplaintDecision: vi.fn(),
  postAdminRefundDecision: vi.fn(),
}))

const transaction = {
  id: 'transaction-1',
  type: 'payment',
  occurred_at: null,
  retained_until: '2029-10-11T00:00:00Z',
  payment_id: null,
  environment: null,
  manual_review: true,
}

const detail = {
  ...transaction,
  payload: { status: 'paid' },
}

beforeEach(() => {
  window.history.replaceState({}, '', '/admin')
  sessionStorage.clear()
  vi.mocked(listAdminRetainedTransactions)
    .mockReset()
    .mockResolvedValue({
      items: [transaction],
      page: 0,
      size: 20,
      total: 1,
    })
  vi.mocked(getAdminRetainedTransaction).mockReset().mockResolvedValue(detail)
  vi.mocked(postAdminRefundDecision).mockReset()
  vi.mocked(postAdminComplaintDecision).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('AdminRetainedTransactionsTab', () => {
  it('keeps an already selected detail when its button is clicked again', async () => {
    const user = userEvent.setup()
    render(<AdminRetainedTransactionsTab />)
    const button = await screen.findByRole('button', { name: '상세 보기' })
    await user.click(button)
    await screen.findByRole('button', { name: '환불 판단 기록' })
    await user.click(button)
    expect(screen.getByRole('button', { name: '환불 판단 기록' })).toBeInTheDocument()
    expect(getAdminRetainedTransaction).toHaveBeenCalledTimes(1)
  })

  it('keeps a newer selection when an older decision response arrives', async () => {
    const second = { ...transaction, id: 'transaction-2' }
    vi.mocked(listAdminRetainedTransactions).mockResolvedValue({
      items: [transaction, second],
      page: 0,
      size: 20,
      total: 2,
    })
    vi.mocked(getAdminRetainedTransaction).mockImplementation(async (id) => ({
      ...detail,
      id,
      payload: { selected: id },
    }))
    let complete!: (value: typeof detail) => void
    vi.mocked(postAdminRefundDecision).mockReturnValue(
      new Promise((resolve) => {
        complete = resolve
      }),
    )
    const user = userEvent.setup()
    render(<AdminRetainedTransactionsTab />)
    const buttons = await screen.findAllByRole('button', { name: '상세 보기' })
    await user.click(buttons[0]!)
    await screen.findByRole('button', { name: '환불 판단 기록' })
    const form = screen.getByRole('heading', { name: '환불 판단 기록' }).closest('form')!
    await user.clear(within(form).getByLabelText('환불 금액(원)'))
    await user.type(within(form).getByLabelText('환불 금액(원)'), '1200')
    await user.type(within(form).getByLabelText('판단 사유'), '첫 거래 판단')
    await user.click(within(form).getByRole('button', { name: '환불 판단 기록' }))
    await waitFor(() => expect(postAdminRefundDecision).toHaveBeenCalledTimes(1))
    await user.click(buttons[1]!)
    await waitFor(() => expect(screen.getByText(/"selected": "transaction-2"/)).toBeInTheDocument())
    await act(async () => {
      complete(detail)
    })
    expect(screen.getByText(/"selected": "transaction-2"/)).toBeInTheDocument()
    expect(screen.getByLabelText('환불 금액(원)')).toHaveValue(0)
  })

  it('uses zero-based API pages and renders unknown legacy metadata safely', async () => {
    render(<AdminRetainedTransactionsTab />)

    expect(await screen.findByText(/환경 미상/)).toBeInTheDocument()
    expect(screen.getByText('증빙 확인 필요')).toBeInTheDocument()
    expect(screen.getByText(/발생 기록 없음/)).toBeInTheDocument()
    expect(screen.getByText(/결제 식별자 미상/)).toBeInTheDocument()
    expect(screen.queryByText(/1970/)).not.toBeInTheDocument()
    expect(listAdminRetainedTransactions).toHaveBeenCalledWith(
      { page: 0, size: 20, type: '', from: undefined, to: undefined },
      expect.any(AbortSignal),
    )
  })

  it('converts date filters to date-time query values', async () => {
    const user = userEvent.setup()
    render(<AdminRetainedTransactionsTab />)
    await screen.findByText(/환경 미상/)

    await user.type(screen.getByLabelText('시작일'), '2026-10-01')
    await user.type(screen.getByLabelText('종료일'), '2026-10-03')
    await user.click(screen.getByRole('button', { name: '조회' }))

    await waitFor(() =>
      expect(listAdminRetainedTransactions).toHaveBeenLastCalledWith(
        expect.objectContaining({
          page: 0,
          from: new Date('2026-10-01T00:00:00').toISOString(),
          to: new Date('2026-10-03T23:59:59.999').toISOString(),
        }),
        expect.any(AbortSignal),
      ),
    )
  })

  it('retries an uncertain saved refund decision with the identical UTC timestamp and body', async () => {
    const operationId = '00000000-0000-0000-0000-000000000001'
    sessionStorage.setItem(
      'admin-operation:anonymous:transaction-1:retained-refund-decision',
      JSON.stringify({
        operation_id: operationId,
        request: {
          status: 'approved',
          amount: 1200,
          recovery_credits: 1.5,
          reason: '고객 요청 확인',
          stop_renewal: true,
          received_at: '2026-10-10T00:00:00.000Z',
        },
      }),
    )
    vi.mocked(postAdminRefundDecision).mockRejectedValue(new Error('network'))
    const user = userEvent.setup()
    render(<AdminRetainedTransactionsTab />)

    await user.click(await screen.findByRole('button', { name: '상세 보기' }))
    expect(
      await screen.findByText(
        '확인되지 않은 판단이 있습니다. 같은 내용으로 결과를 다시 확인하세요.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('환불 금액(원)')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '환불 판단 기록' }))

    await waitFor(() => expect(postAdminRefundDecision).toHaveBeenCalledTimes(1))
    expect(postAdminRefundDecision).toHaveBeenCalledWith('transaction-1', {
      operation_id: operationId,
      status: 'approved',
      amount: 1200,
      recovery_credits: 1.5,
      reason: '고객 요청 확인',
      stop_renewal: true,
      received_at: '2026-10-10T00:00:00.000Z',
    })
  })

  it('clears a known validation failure and assigns a new operation id before editing', async () => {
    const user = userEvent.setup()
    vi.mocked(postAdminRefundDecision)
      .mockRejectedValueOnce(new ApiError(422, '접수 시각이 올바르지 않습니다.'))
      .mockResolvedValueOnce(detail)
    render(<AdminRetainedTransactionsTab />)

    await user.click(await screen.findByRole('button', { name: '상세 보기' }))
    await screen.findByRole('button', { name: '환불 판단 기록' })
    const refundForm = screen.getByRole('heading', { name: '환불 판단 기록' }).closest('form')
    if (!refundForm) throw new Error('환불 판단 폼이 없습니다')
    await user.clear(within(refundForm).getByLabelText('환불 금액(원)'))
    await user.type(within(refundForm).getByLabelText('환불 금액(원)'), '1200')
    await user.type(within(refundForm).getByLabelText('판단 사유'), '접수 시각을 확인했습니다')
    await user.click(within(refundForm).getByRole('button', { name: '환불 판단 기록' }))

    expect(await screen.findByText('접수 시각이 올바르지 않습니다.')).toBeInTheDocument()
    expect(within(refundForm).getByLabelText('환불 금액(원)')).toBeEnabled()
    const firstRequest = vi.mocked(postAdminRefundDecision).mock.calls[0]?.[1]
    expect(firstRequest).toBeDefined()
    expect(
      sessionStorage.getItem('admin-operation:anonymous:transaction-1:retained-refund-decision'),
    ).toBeNull()

    await user.click(within(refundForm).getByRole('button', { name: '환불 판단 기록' }))
    await waitFor(() => expect(postAdminRefundDecision).toHaveBeenCalledTimes(2))
    expect(vi.mocked(postAdminRefundDecision).mock.calls[1]?.[1].operation_id).not.toBe(
      firstRequest?.operation_id,
    )
  })
})
