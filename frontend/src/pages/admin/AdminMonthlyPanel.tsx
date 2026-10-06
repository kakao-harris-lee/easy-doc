import { useEffect, useState } from 'react'
import {
  listAdminCreditTransactions,
  listAdminPayments,
  readAdminMonthlyHistory,
  readAdminMonthlySummary,
} from '../../api/admin'
import type {
  AdminCreditEvent,
  AdminMonthlyHistory,
  AdminMonthlySummary,
  AdminPaymentEvent,
  AdminReportPage,
} from '../../api/adminMonthlyTypes'
import { ApiError } from '../../api/client'
import { getSubscription, type SubscriptionOverview } from '../../api/subscriptions'
import { PaymentActions } from '../../components/subscription/PaymentActions'
import { Button } from '../../components/ui/Button'
import { formatCredits } from '../../lib/credits'
import { SERVICE_START_MONTH } from './adminMonths'
import { useAdminQuery, adminPageNumber } from './useAdminQuery'
import { AdminBillingPanel } from './AdminBillingPanel'
import { AdminCreditAdjustment } from './AdminCreditAdjustment'

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const date = (value: string | null, timezone: string) =>
  value ? new Date(value).toLocaleString('ko-KR', { timeZone: timezone }) : '발생 월 미상'
const kindLabels: Record<string, string> = {
  grant: '지급',
  consume: '사용 확정',
  reserve: '처리 중 확보',
  release: '확보 해제',
  adjust: '조정',
  cycle_set: '주기 설정',
  cycle_reset: '주기 종료',
}
const statusLabels: Record<string, string> = {
  active: '이용 중',
  canceling: '종료 예정',
  expired: '종료',
  past_due: '결제 실패',
  paid: '결제 완료',
  partially_refunded: '부분 환불',
  refunded: '환불 완료',
  failed: '실패',
  pending: '대기',
  processing: '처리 중',
  manual_review: '확인 필요',
}
const reasonLabels: Record<string, string> = {
  manual: '관리자 조정',
  refund: '크레딧 복구',
  plan_monthly: '주기 제공',
  signup: '가입 지급',
  conversion: '변환',
  convert: '변환',
  reconversion: '재변환',
  action_guide: '행동 안내',
  illustration_suggestion: '그림 제안',
  cycle_end: '주기 종료',
}
function Pages({
  name,
  page,
  total,
  onChange,
}: {
  name: string
  page: number
  total: number
  onChange: (page: number) => void
}) {
  return (
    <nav aria-label={`${name} 페이지`} className="my-3 flex items-center gap-3">
      <Button disabled={page <= 1} onClick={() => onChange(page - 1)}>
        이전
      </Button>
      <span>
        {page}쪽 · 총 {total}건
      </span>
      <Button disabled={page * 20 >= total} onClick={() => onChange(page + 1)}>
        다음
      </Button>
    </nav>
  )
}
export function AdminMonthlyPanel({
  workspaceId,
  month,
  onChanged,
  onMonthChange,
  refreshToken = 0,
}: {
  refreshToken?: number
  workspaceId: string
  month: string
  onChanged: () => void
  onMonthChange: (month: string) => void
}) {
  const [billingPending, setBillingPending] = useState(true)
  const [billingError, setBillingError] = useState(false)
  const [localReload, setReload] = useState(0)
  const reload = `${refreshToken}:${localReload}`
  const [summary, setSummary] = useState<AdminMonthlySummary | null>(null)
  const [history, setHistory] = useState<AdminMonthlyHistory | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creditError, setCreditError] = useState<string | null>(null)
  const [paymentError, setPaymentError] = useState<string | null>(null)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [query, updateQuery] = useAdminQuery()
  const creditPage = adminPageNumber(query.get('credit_page'))
  const paymentPage = adminPageNumber(query.get('payment_page'))
  const kind = query.get('credit_kind') ?? ''
  const setCreditPage = (value: number) => updateQuery({ credit_page: String(value) })
  const setPaymentPage = (value: number) => updateQuery({ payment_page: String(value) })
  const setKind = (value: string) =>
    updateQuery({ credit_kind: value || undefined, credit_page: '1' })
  const [credits, setCredits] = useState<{
    key: string
    data: AdminReportPage<AdminCreditEvent>
  } | null>(null)
  const [payments, setPayments] = useState<{
    key: string
    data: AdminReportPage<AdminPaymentEvent>
  } | null>(null)
  const creditKey = `${workspaceId}|${month}|${creditPage}|${kind}|${reload}`
  const paymentKey = `${workspaceId}|${month}|${paymentPage}|${reload}`
  useEffect(() => {
    const controller = new AbortController()
    getSubscription(workspaceId, controller.signal, true)
      .then((view) => {
        if (!controller.signal.aborted) {
          setBillingPending(Boolean(view.pending))
          setBillingError(false)
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setBillingPending(true)
          setBillingError(true)
        }
      })
    return () => controller.abort()
  }, [workspaceId, reload])
  useEffect(() => {
    const controller = new AbortController()
    readAdminMonthlySummary(workspaceId, month, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setSummary(value)
          setError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setError(cause instanceof ApiError ? cause.message : '월별 결산을 불러오지 못했습니다.')
      })
    return () => controller.abort()
  }, [workspaceId, month, reload])
  useEffect(() => {
    const controller = new AbortController()
    readAdminMonthlyHistory(workspaceId, Number(month.slice(0, 4)), controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setHistory(value)
          setHistoryError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setHistoryError(
            cause instanceof ApiError ? cause.message : '월별 비교를 불러오지 못했습니다.',
          )
      })
    return () => controller.abort()
  }, [workspaceId, month, reload])
  useEffect(() => {
    const controller = new AbortController()
    listAdminCreditTransactions(
      workspaceId,
      { month, page: creditPage, kind: kind || undefined },
      controller.signal,
    )
      .then((value) => {
        if (!controller.signal.aborted) {
          setCredits({ key: creditKey, data: value })
          setCreditError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setCreditError(
            cause instanceof ApiError ? cause.message : '크레딧 거래를 불러오지 못했습니다.',
          )
      })
    return () => controller.abort()
  }, [workspaceId, month, creditPage, kind, creditKey])
  useEffect(() => {
    const controller = new AbortController()
    listAdminPayments(workspaceId, { month, page: paymentPage }, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setPayments({ key: paymentKey, data: value })
          setPaymentError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setPaymentError(
            cause instanceof ApiError ? cause.message : '결제 내역을 불러오지 못했습니다.',
          )
      })
    return () => controller.abort()
  }, [workspaceId, month, paymentPage, paymentKey])
  function changed() {
    setReload((value) => value + 1)
    onChanged()
  }
  if (!summary || summary.month !== month)
    return (
      <div>
        <AdminBillingPanel workspaceId={workspaceId} onChanged={changed} refreshToken={reload} />
        <Button onClick={changed}>결산 다시 조회</Button>
        {error ? <p role="alert">{error}</p> : <p role="status">월별 결산을 불러오는 중입니다…</p>}
      </div>
    )
  const { current, credits: c, usage, payments: p, completeness } = summary
  const creditData = credits?.key === creditKey ? credits.data : null
  const paymentData = payments?.key === paymentKey ? payments.data : null
  const rows: Array<[string, number]> = [
    ['월초 보유', c.opening],
    ['지급', c.granted],
    ['사용', c.consumed],
    ['소멸', c.expired],
    ['조정 순증감', c.adjustment],
    ['과거 주기 변경 순증감', c.cycle_net],
    [summary.is_current_month ? '현재까지 보유' : '월말 보유', c.closing],
  ]
  return (
    <div className="space-y-5">
      <AdminBillingPanel workspaceId={workspaceId} onChanged={changed} refreshToken={reload} />
      {error && <p role="alert">{error}</p>}
      <section aria-label="현재 크레딧" className="rounded-xl border border-border p-4">
        <h4 className="font-semibold">현재 크레딧</h4>
        <p className="text-sm text-muted-foreground">
          {date(current.as_of, summary.timezone)} 기준 · {summary.timezone} · 조회 월과 별개인 현재
          상태
        </p>
        <dl className="my-3 grid gap-3 sm:grid-cols-3">
          <div>
            <dt>사용 가능 크레딧</dt>
            <dd className="text-3xl font-bold">{formatCredits(current.available)}</dd>
          </div>
          <div>
            <dt>보유 크레딧</dt>
            <dd className="text-xl">{formatCredits(current.balance)}</dd>
          </div>
          <div>
            <dt>처리 중 확보 크레딧</dt>
            <dd className="text-xl">{formatCredits(current.reserved)}</dd>
          </div>
        </dl>
        <p className="text-sm">
          사용 가능 = 보유 − 처리 중 확보. 작업 시작 시 임시 확보하고, 성공하면 사용을 확정하며
          실패하면 확보를 해제합니다.
        </p>
        <details className="mt-2 text-sm">
          <summary>확보 크레딧 계산 예시</summary>
          <p>
            보유 100에서 10 확보: 보유 100 · 확보 10 · 사용 가능 90. 성공: 보유 90 · 확보 0 · 사용
            가능 90. 실패: 보유 100 · 확보 0 · 사용 가능 100.
          </p>
        </details>
        <p className="mt-3 text-sm">
          구독 상태{' '}
          {current.subscription_status
            ? (statusLabels[current.subscription_status] ?? current.subscription_status)
            : '없음'}{' '}
          · 주기 제공량 {formatCredits(current.allowance)}
        </p>
        <p className="text-sm">
          {current.cycle_ends_at === null ? (
            '현재 이용 주기 없음'
          ) : (
            <>
              {new Date(current.cycle_ends_at).getTime() <= new Date(current.as_of).getTime()
                ? '종료된 이용 주기'
                : '현재 이용 주기'}
              :{' '}
              {current.cycle_started_at
                ? date(current.cycle_started_at, summary.timezone)
                : '시작일 확인 필요'}{' '}
              ~ {date(current.cycle_ends_at, summary.timezone)} · 주기 종료 시 미사용량 이월 없음
            </>
          )}
        </p>
      </section>
      <AdminCreditAdjustment
        workspaceId={workspaceId}
        balance={current.balance}
        reserved={current.reserved}
        revision={current.revision}
        onChanged={changed}
      />
      <section aria-label="선택 월 크레딧 결산">
        <h4 className="font-semibold">{month} 크레딧 결산</h4>
        <p className="text-sm text-muted-foreground">
          {summary.timezone} 달력 월 기준 · 사용은 소비 확정 시점에 집계하며 확보·해제는 제외합니다.
        </p>
        <table className="usage-table">
          <caption>선택 월의 전체 크레딧 거래 합계</caption>
          <tbody>
            {rows.map(([label, value]) => (
              <tr key={label}>
                <th scope="row">{label}</th>
                <td>{formatCredits(value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="text-sm">
          월말 보유 = 월초 보유 + 지급 − 사용 − 소멸 + 조정 + 과거 주기 변경 순증감
        </p>
        <details className="mt-2">
          <summary>결산 시점 확보·사용 가능 및 대사</summary>
          <p>
            처리 중 확보 {formatCredits(c.reserved)} · 사용 가능 {formatCredits(c.available)}
          </p>
          <p>
            원장·현재 계정 {completeness.ledger_matches_account ? '일치' : '확인 필요'} · 월별 증감{' '}
            {completeness.monthly_equation_matches ? '일치' : '확인 필요'}
          </p>
        </details>
        {c.legacy_cycle_count > 0 && (
          <p className="text-sm">
            과거 주기 변경 {c.legacy_cycle_count}건은 지급·소멸을 분리 복원할 수 없어 순증감으로
            표시합니다.
          </p>
        )}
        {completeness.warnings.length > 0 && (
          <div role="status">
            <p>자료 확인 필요</p>
            <ul>
              {completeness.warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          </div>
        )}
      </section>
      <section aria-label="월별 크레딧 거래">
        <h4 className="font-semibold">크레딧 거래 내역</h4>
        <label className="field">
          거래 종류
          <select
            value={kind}
            onChange={(event) => {
              setKind(event.target.value)
              setCreditPage(1)
              setCreditError(null)
            }}
          >
            <option value="">전체</option>
            {Object.entries(kindLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        {creditError ? (
          <p role="alert">{creditError}</p>
        ) : !creditData ? (
          <p role="status">거래를 불러오는 중입니다…</p>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="usage-table">
                <caption>{month} 전체 거래에서 종류별 조회</caption>
                <thead>
                  <tr>
                    {[
                      '일시',
                      '종류',
                      '보유 증감',
                      '확보 증감',
                      '사유·메모',
                      '처리자',
                      '연결 결제',
                    ].map((label) => (
                      <th key={label} scope="col">
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {creditData.items.length === 0 && (
                    <tr>
                      <td colSpan={7}>거래가 없습니다.</td>
                    </tr>
                  )}
                  {creditData.items.map((item) => (
                    <tr key={item.id}>
                      <td>{date(item.created_at, summary.timezone)}</td>
                      <td>{kindLabels[item.kind] ?? item.kind}</td>
                      <td>{formatCredits(item.balance_delta)}</td>
                      <td>{formatCredits(item.reserved_delta)}</td>
                      <td>
                        {reasonLabels[item.reason] ?? item.reason}
                        <br />
                        {item.note}
                        {item.granted_amount !== null && item.granted_amount !== undefined && (
                          <details>
                            <summary>주기 변경 상세</summary>
                            <p>
                              지급 {formatCredits(item.granted_amount)} · 소멸{' '}
                              {formatCredits(item.expired_amount ?? 0)} · 조정{' '}
                              {formatCredits(item.adjustment_amount ?? 0)}
                            </p>
                            <p>
                              이전 주기{' '}
                              {item.cycle_started_at_before
                                ? date(item.cycle_started_at_before, summary.timezone)
                                : '없음'}{' '}
                              ~{' '}
                              {item.cycle_ends_at_before
                                ? date(item.cycle_ends_at_before, summary.timezone)
                                : '없음'}
                            </p>
                            <p>
                              변경 주기{' '}
                              {item.cycle_started_at_after
                                ? date(item.cycle_started_at_after, summary.timezone)
                                : '없음'}{' '}
                              ~{' '}
                              {item.cycle_ends_at_after
                                ? date(item.cycle_ends_at_after, summary.timezone)
                                : '없음'}
                            </p>
                          </details>
                        )}
                      </td>
                      <td>{item.actor_user_id ?? '시스템'}</td>
                      <td>{item.payment_id ?? '연결 없음'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pages
              name="크레딧 거래"
              page={creditPage}
              total={creditData.total}
              onChange={setCreditPage}
            />
          </>
        )}
      </section>
      <section aria-label="결제·환불 내역">
        <h4 className="font-semibold">{month} 결제·환불 내역</h4>
        {billingError && (
          <p role="alert">
            결제 진행 상태를 확인하지 못해 환불을 잠시 사용할 수 없습니다. 새로고침해 주세요.
          </p>
        )}
        <p>
          실제 결제 {won(p.paid_krw)} · 환불 {won(p.refunded_krw)} · 차액 {won(p.net_krw)}
        </p>
        <p className="text-sm">
          테스트 결제 {won(p.test_paid_krw)} · 테스트 환불 {won(p.test_refunded_krw)} (실제 결제
          합계 제외)
        </p>
        <p className="text-sm text-muted-foreground">
          확정 발생 시점 기준입니다. 실패·처리 중 결제는 성공 합계에 포함하지 않습니다. 현금 환불과
          크레딧 복구는 별도 거래입니다.
        </p>
        {(p.unknown_date_paid_krw > 0 ||
          p.unknown_date_refund_krw > 0 ||
          p.test_unknown_date_paid_krw > 0 ||
          p.test_unknown_date_refund_krw > 0) && (
          <p role="status">
            발생 월 미상 (월 합계 제외): 실제 결제 {won(p.unknown_date_paid_krw)} / 환불{' '}
            {won(p.unknown_date_refund_krw)} · 테스트 결제 {won(p.test_unknown_date_paid_krw)} /
            환불 {won(p.test_unknown_date_refund_krw)}
          </p>
        )}
        {paymentError ? (
          <p role="alert">{paymentError}</p>
        ) : !paymentData ? (
          <p role="status">결제를 불러오는 중입니다…</p>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="usage-table">
                <caption>선택 월 확정 내역 및 발생 월 미상 과거 내역</caption>
                <thead>
                  <tr>
                    {[
                      '발생일 / 원 주문일',
                      '구분',
                      '금액',
                      '현재 결제 상태·플랜',
                      '식별자·크레딧 연결',
                      '관리',
                    ].map((label) => (
                      <th key={label} scope="col">
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {paymentData.items.length === 0 && (
                    <tr>
                      <td colSpan={6}>내역이 없습니다.</td>
                    </tr>
                  )}
                  {paymentData.items.map((item) => (
                    <tr key={item.id}>
                      <td>
                        {date(item.occurred_at, summary.timezone)}
                        <br />
                        <small>주문 {date(item.original_created_at, summary.timezone)}</small>
                      </td>
                      <td>
                        {item.is_test ? '테스트 ' : '실제 '}
                        {item.kind === 'refund'
                          ? '환불'
                          : item.kind === 'refund_attempt'
                            ? '환불 시도'
                            : item.kind === 'attempt'
                              ? '결제 시도'
                              : '결제'}
                      </td>
                      <td>{won(item.amount_krw)}</td>
                      <td>
                        {statusLabels[item.status] ?? item.status} · {item.plan_id}
                      </td>
                      <td>
                        결제 {item.payment_id}
                        <br />
                        거래 {item.id}
                        <br />
                        크레딧 {item.credit_transaction_id ?? '연결 없음'}
                      </td>
                      <td>
                        {item.kind === 'payment' &&
                          ['paid', 'partially_refunded'].includes(item.status) && (
                            <PaymentActions
                              key={`${item.payment_id}:${item.refunded_amount_krw}`}
                              workspace={workspaceId}
                              admin
                              pending={billingPending}
                              payment={{
                                id: item.payment_id,
                                plan_id: item.plan_id,
                                amount: item.original_amount_krw,
                                refunded_amount: item.refunded_amount_krw,
                                created_at: item.original_created_at,
                                status:
                                  item.status as SubscriptionOverview['payments'][number]['status'],
                                provider:
                                  item.provider as SubscriptionOverview['payments'][number]['provider'],
                              }}
                              onChanged={changed}
                            />
                          )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pages
              name="결제 내역"
              page={paymentPage}
              total={paymentData.total}
              onChange={setPaymentPage}
            />
          </>
        )}
      </section>
      <details>
        <summary>사용량 상세 · 토큰·AI 처리 원가</summary>
        <p>
          변환 문서 {usage.documents.toLocaleString('ko-KR')}건 (완료된 LLM 변환 호출이 있는 고유
          문서)
        </p>
        <p>
          입력 토큰 {usage.input_tokens.toLocaleString('ko-KR')} · 출력 토큰{' '}
          {usage.output_tokens.toLocaleString('ko-KR')}
        </p>
        <p>
          AI 처리 원가(추정) · 확인된 비용 합계{' '}
          {usage.known_cost_usd === null ? '모름' : `$${usage.known_cost_usd}`} · 비용 미상{' '}
          {usage.unknown_cost_calls}건
        </p>
        <p className="text-sm">공급자 비용(USD)이며 고객 결제액(원)과 다릅니다.</p>
        <ul>
          {Object.entries(usage.credits_by_reason).map(([reason, amount]) => (
            <li key={reason}>
              {reasonLabels[reason] ?? `기타 (${reason})`}: {formatCredits(amount)}크레딧
            </li>
          ))}
        </ul>
        {usage.estimated_legacy_credits > 0 && (
          <p>
            과거 추정 사용량 {formatCredits(usage.estimated_legacy_credits)}크레딧 · 실제 소비
            원장과 별도
          </p>
        )}
      </details>
      <details>
        <summary>{month.slice(0, 4)}년 월별 비교</summary>
        {historyError ? (
          <p role="alert">{historyError}</p>
        ) : !history ? (
          <p role="status">월별 비교를 불러오는 중입니다…</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="usage-table">
              <caption>{history.timezone} 기준 월별 비교 · 현재 월은 현재까지</caption>
              <thead>
                <tr>
                  {['월', '지급', '사용', '소멸', '월말 보유', '실제 결제', '실제 환불'].map(
                    (label) => (
                      <th key={label} scope="col">
                        {label}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {history.items
                  .filter((item) => item.month >= SERVICE_START_MONTH)
                  .map((item) => (
                    <tr key={item.month}>
                      <th scope="row">
                        <Button variant="ghost" onClick={() => onMonthChange(item.month)}>
                          {item.month}
                        </Button>
                      </th>
                      <td>{formatCredits(item.credits.granted)}</td>
                      <td>{formatCredits(item.credits.consumed)}</td>
                      <td>{formatCredits(item.credits.expired)}</td>
                      <td>{formatCredits(item.credits.closing)}</td>
                      <td>{won(item.payments.paid_krw)}</td>
                      <td>{won(item.payments.refunded_krw)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </details>
    </div>
  )
}
