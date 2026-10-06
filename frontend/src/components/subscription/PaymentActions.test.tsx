import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { getTossReceipt, refundTossPayment } from '../../api/subscriptions'
import { readAdminMonthlySummary } from '../../api/admin'
import { PaymentActions } from './PaymentActions'

vi.mock('../../api/subscriptions', () => ({ getTossReceipt: vi.fn(), refundTossPayment: vi.fn() }))
vi.mock('../../api/admin', () => ({ readAdminMonthlySummary: vi.fn() }))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(readAdminMonthlySummary).mockResolvedValue({
    current: { balance: 50, reserved: 0, available: 50, revision: 1 },
  } as Awaited<ReturnType<typeof readAdminMonthlySummary>>)
})
const payment = {
  id: 'payment',
  plan_id: 'start',
  amount: 1000,
  status: 'paid' as const,
  created_at: '2026-09-13',
  provider: 'toss_test' as const,
  refunded_amount: 0,
}
it('keeps the refund operation id when a response is lost', async () => {
  vi.mocked(refundTossPayment).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({
    operation_id: 'op',
    workspace_id: 'workspace',
    payment_id: 'payment',
    amount: 400,
    recovery_credits: 0,
    stop_renewal: true,
    reason: '고객 요청',
    status: 'pending',
    created_at: '',
    updated_at: '',
  })
  const changed = vi.fn(),
    user = userEvent.setup()
  render(<PaymentActions workspace="workspace" payment={payment} admin onChanged={changed} />)
  await user.click(screen.getByText('테스트 환불'))
  await user.click(screen.getByRole('button', { name: '현재 크레딧 확인' }))
  await user.type(await screen.findByLabelText('처리 사유'), '고객 요청')
  await user.click(screen.getByLabelText('환불액, 회수량, 갱신 중단 여부와 사유를 확인했습니다.'))
  await user.clear(screen.getByLabelText('환불 금액 (원)'))
  await user.type(screen.getByLabelText('환불 금액 (원)'), '400')
  await user.click(screen.getByRole('button', { name: '테스트 환불 실행' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('결과를 확인하지 못했습니다')
  await user.click(screen.getByRole('button', { name: '같은 환불 요청 결과 확인' }))
  expect(vi.mocked(refundTossPayment).mock.calls[1]).toEqual(
    vi.mocked(refundTossPayment).mock.calls[0],
  )
  expect(refundTossPayment).toHaveBeenCalledWith(
    'workspace',
    'payment',
    expect.objectContaining({
      operation_id: expect.any(String),
      amount: 400,
      recovery_credits: 0,
      expected_revision: 1,
    }),
  )
  expect(changed).toHaveBeenCalledOnce()
})
it('rejects a non-HTTPS receipt and opens a verified link without sending a referrer', async () => {
  vi.mocked(getTossReceipt)
    .mockResolvedValueOnce({ receipt_url: 'javascript:alert(1)' })
    .mockResolvedValueOnce({ receipt_url: 'https://receipt.tosspayments.com/test' })
  const user = userEvent.setup()
  render(
    <PaymentActions workspace="workspace" payment={payment} admin={false} onChanged={vi.fn()} />,
  )
  await user.click(screen.getByRole('button', { name: '테스트 영수증 확인' }))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '테스트 영수증 확인' }))
  expect(await screen.findByRole('link')).toHaveAttribute('rel', 'noopener noreferrer')
})
