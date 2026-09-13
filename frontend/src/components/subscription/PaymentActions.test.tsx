import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { getTossReceipt, refundTossPayment } from '../../api/subscriptions'
import { PaymentActions } from './PaymentActions'

vi.mock('../../api/subscriptions', () => ({ getTossReceipt: vi.fn(), refundTossPayment: vi.fn() }))
beforeEach(() => vi.resetAllMocks())
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
    mock_enabled: false,
    plans: [],
    subscription: null,
    payments: [],
    pending: true,
  })
  const changed = vi.fn(),
    user = userEvent.setup()
  render(<PaymentActions workspace="workspace" payment={payment} admin onChanged={changed} />)
  await user.click(screen.getByText('테스트 환불'))
  await user.clear(screen.getByRole('spinbutton'))
  await user.type(screen.getByRole('spinbutton'), '400')
  await user.click(screen.getByRole('button', { name: '테스트 환불 실행' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('결과를 확인하지 못했습니다')
  await user.click(screen.getByRole('button', { name: '테스트 환불 실행' }))
  expect(vi.mocked(refundTossPayment).mock.calls[1]).toEqual(
    vi.mocked(refundTossPayment).mock.calls[0],
  )
  expect(refundTossPayment).toHaveBeenCalledWith('workspace', 'payment', expect.any(String), 400)
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
