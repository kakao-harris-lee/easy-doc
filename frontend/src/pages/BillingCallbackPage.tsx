import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  completeTossBilling,
  getSubscription,
  type SubscriptionOverview,
} from '../api/subscriptions'
import { ApiError } from '../api/client'
import { BILLING_CONTEXT } from '../billing/toss'
import { Button } from '../components/ui/Button'

export function BillingCallbackPage() {
  // Keep the one-use credential only in memory. Remove it from browser history before other interactions.
  const [callback] = useState(() => {
    const query = new URLSearchParams(location.search)
    const auth = query.get('authKey')
    const customer = query.get('customerKey')
    return { auth, customer, code: query.get('code') }
  })
  const started = useRef(false)
  const [busy, setBusy] = useState(true)
  const [message, setMessage] = useState('카드 등록과 결제 결과를 확인하고 있습니다…')
  const [pollContext, setPollContext] = useState<{
    workspace: string
    session: string
    replacement: boolean
  } | null>(null)
  function describe(result: SubscriptionOverview, session: string, replacement = false) {
    if (result.manual_review)
      return '결제 결과를 관리자가 확인하고 있습니다. 새 결제를 시작하지 말고 사용량 화면에서 상태를 확인해 주세요.'
    if (result.pending) return '결제 결과를 확인 중입니다. 자동으로 다시 확인합니다.'
    if (result.payments.find((p) => p.id === session)?.status === 'failed')
      return '결제가 거절되었습니다. 사용량 화면에서 상태를 확인해 주세요.'
    if (replacement) return '카드가 변경되었습니다. 기존 이용 기간과 크레딧은 유지됩니다.'
    return result.billing_environment === 'toss_live'
      ? '정기결제가 승인되었습니다. 사용량 화면에서 이용 기간과 크레딧을 확인하세요.'
      : '테스트 구독이 적용되었습니다. 실제 청구는 없습니다.'
  }
  const [retry, setRetry] = useState(false)
  async function finish() {
    setBusy(true)
    setRetry(false)
    try {
      const context = JSON.parse(sessionStorage.getItem(BILLING_CONTEXT) ?? 'null') as {
        workspace: string
        session: string
        fail: boolean
        purpose?: string
      } | null
      if (!context || !callback.auth || !callback.customer) {
        setMessage(
          callback.code === 'NOT_SUPPORTED_CARD_TYPE'
            ? '지원하지 않는 카드 종류입니다. 다른 카드로 등록해 주세요.'
            : callback.code === 'PAY_PROCESS_CANCELED'
              ? '카드 등록을 취소했습니다. 사용량 화면에서 다시 시작할 수 있습니다.'
              : '카드 등록이 취소되었거나 등록 정보가 만료되었습니다. 사용량 화면에서 다시 시작하세요.',
        )
        return
      }
      const result = await completeTossBilling(
        context.workspace,
        context.session,
        callback.customer,
        callback.auth,
        context.fail,
      )
      setMessage(describe(result, context.session, context.purpose === 'replace_card'))
      if (result.pending && !result.manual_review)
        setPollContext({
          workspace: context.workspace,
          session: context.session,
          replacement: context.purpose === 'replace_card',
        })
      sessionStorage.removeItem(BILLING_CONTEXT)
    } catch (cause) {
      setMessage(
        cause instanceof ApiError
          ? cause.message
          : '결과를 확인하지 못했습니다. 다시 확인해 주세요.',
      )
      setRetry(true)
    } finally {
      setBusy(false)
    }
  }
  useEffect(() => {
    history.replaceState(history.state, '', '/billing/callback')
    if (!started.current) {
      started.current = true
      void finish()
    }
    // Retry uses the same in-memory callback; rerenders must not issue a second request.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useEffect(() => {
    if (!pollContext) return
    const controller = new AbortController()
    const timer = window.setInterval(() => {
      void getSubscription(pollContext.workspace, controller.signal)
        .then((result) => {
          if (controller.signal.aborted) return
          setMessage(describe(result, pollContext.session, pollContext.replacement))
          if (!result.pending || result.manual_review) setPollContext(null)
        })
        .catch(() => {
          /* Keep the pending result; do not create a new order. */
        })
    }, 5000)
    return () => {
      controller.abort()
      window.clearInterval(timer)
    }
  }, [pollContext])
  return (
    <section className="mx-auto max-w-xl space-y-4 p-6">
      <h1 className="text-xl font-semibold">카드 등록 및 결제 확인</h1>
      <p role="status">{message}</p>
      {retry && (
        <Button disabled={busy} onClick={() => void finish()}>
          결과 다시 확인
        </Button>
      )}
      <Link className="block underline" to="/usage">
        사용량으로 돌아가기
      </Link>
    </section>
  )
}
