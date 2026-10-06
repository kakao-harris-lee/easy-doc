import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { beginTossBilling, type TestSubscriptionPlanId } from '../api/subscriptions'

export const BILLING_CONTEXT = 'easydoc.billing.context'
/** Only opaque correlation values go to sessionStorage; never authKey, billingKey or card details. */
export async function openTossBilling(
  workspace: string,
  plan: TestSubscriptionPlanId,
  fail: boolean,
  purpose: 'purchase' | 'replace_card' = 'purchase',
  consentVersion?: string,
) {
  const session = await beginTossBilling(workspace, plan, purpose, consentVersion)
  const live = session.billing_environment === 'toss_live'
  if (!session.client_key.startsWith(live ? 'live_ck_' : 'test_ck_'))
    throw new Error('결제 환경과 카드 등록 키가 일치하지 않습니다.')
  if (live && fail) throw new Error('실결제에서는 실패 테스트를 사용할 수 없습니다.')
  sessionStorage.setItem(
    BILLING_CONTEXT,
    JSON.stringify({
      workspace,
      session: session.session_id,
      fail,
      purpose,
      environment: session.billing_environment,
    }),
  )
  const toss = await loadTossPayments(session.client_key)
  const callback = `${location.origin}/billing/callback`
  await toss.payment({ customerKey: session.customer_key }).requestBillingAuth({
    method: 'CARD',
    successUrl: callback,
    failUrl: callback,
  })
}
