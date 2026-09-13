import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { beginTossBilling } from '../api/subscriptions'

export const BILLING_CONTEXT = 'easydoc.billing.context'
/** Only opaque correlation values go to sessionStorage; never authKey, billingKey or card details. */
export async function openTossBilling(workspace: string, plan: string, fail: boolean) {
  const session = await beginTossBilling(workspace, plan)
  if (!session.client_key.startsWith('test_ck_')) throw new Error('테스트 결제 키가 필요합니다.')
  sessionStorage.setItem(
    BILLING_CONTEXT,
    JSON.stringify({ workspace, session: session.session_id, fail }),
  )
  const toss = await loadTossPayments(session.client_key)
  const callback = `${location.origin}/billing/callback`
  await toss.payment({ customerKey: session.customer_key }).requestBillingAuth({
    method: 'CARD',
    successUrl: callback,
    failUrl: callback,
  })
}
