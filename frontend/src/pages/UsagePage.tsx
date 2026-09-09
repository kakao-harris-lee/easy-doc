import { useEffect, useId, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { createInvoiceRequest, listInvoiceRequests } from '../api/invoices'
import { getWorkspaceUsage } from '../api/usage'
import type {
  CreditReason,
  CreditTransaction,
  CreditTransactionKind,
  InvoiceRequestCreate,
  InvoiceRequestResponse,
  InvoiceRequestStatus,
  PurposeUsageItem,
  WorkspaceCreditsResponse,
  WorkspaceUsageResponse,
} from '../api/types'
import { HISTORY_PATH } from '../routes/paths'
import { useWorkspace } from '../workspace/context'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/Button'

const LOAD_ERROR_MESSAGE = '사용량을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 크레딧 계정을 불러오지 못했을 때 쓰는 문구 — 사용량 조회 실패와 다른 자원이라 문구도 나눈다. */
const CREDITS_LOAD_ERROR_MESSAGE = '크레딧 계정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 거래 종류를 사람이 읽는 말로. `PURPOSE_LABEL`과 같은 이유로 `Record<string, string>`이다. */
const KIND_LABEL: Record<string, string> = {
  grant: '부여',
  reserve: '예약',
  consume: '소비',
  release: '해제',
  adjust: '조정',
}

/** 거래 사유를 사람이 읽는 말로. */
const REASON_LABEL: Record<string, string> = {
  signup: '가입',
  plan_monthly: '월 구독',
  manual: '수동',
  refund: '환급',
  conversion: '문서 변환',
}

/** 부호를 명시한다 — 양수도 `+`를 붙인다(자바스크립트 기본 표기는 양수 부호를 생략한다). */
function formatSignedCredits(value: number): string {
  return value > 0 ? `+${value.toLocaleString('ko-KR')}` : value.toLocaleString('ko-KR')
}

/**
 * 크레딧 계정 요약 카드 — 가용·잔액·예약 중, 집행이 꺼져 있으면 그 사실을 덧붙인다.
 *
 * `signup_grant_skipped`(계약 2.29.0)가 참이면 가입 크레딧이 재수령되지 않았다는 안내를
 * 덧붙인다 — 서버가 이메일 인증 전에는 이 값을 항상 거짓으로 채워 주므로 화면은 값을
 * 그대로 보여주면 된다(따로 인증 상태를 확인하지 않는다).
 */
function CreditsCard({ credits }: { credits: WorkspaceCreditsResponse }) {
  return (
    <div className="rounded-[12px] border border-border bg-card p-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
      <h2 className="text-[15px] font-semibold text-foreground">크레딧</h2>
      <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div>
          <dt className="text-sm text-muted-foreground">가용</dt>
          <dd className="text-xl font-bold tabular-nums text-foreground">
            {credits.available.toLocaleString('ko-KR')}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">잔액</dt>
          <dd className="text-xl font-bold tabular-nums text-foreground">
            {credits.balance.toLocaleString('ko-KR')}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">예약 중</dt>
          <dd className="text-xl font-bold tabular-nums text-foreground">
            {credits.reserved.toLocaleString('ko-KR')}
          </dd>
        </div>
      </dl>
      {!credits.enforced && (
        <p className="mt-3 text-sm text-muted-foreground">(지금은 집행되지 않습니다)</p>
      )}
      {credits.signup_grant_skipped && (
        <p className="mt-3 text-sm text-muted-foreground">
          이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.
        </p>
      )}
    </div>
  )
}

/** 거래 한 줄 — 종류·사유 라벨은 계약이 값을 늘려도 화면이 죽지 않도록 원래 값으로 대체한다. */
function CreditTransactionRow({ transaction }: { transaction: CreditTransaction }) {
  const kind: CreditTransactionKind | string = transaction.kind
  const reason: CreditReason | string = transaction.reason
  return (
    <tr>
      <th scope="row">{KIND_LABEL[kind] ?? kind}</th>
      <td className="tabular-nums">{formatSignedCredits(transaction.credits)}</td>
      <td>{REASON_LABEL[reason] ?? reason}</td>
      <td>{transaction.note ?? '—'}</td>
      <td>
        {transaction.document_id !== null ? (
          <Link
            className="text-primary underline-offset-4 hover:underline"
            to={HISTORY_PATH}
            aria-label={`문서 ${transaction.document_id} 보기`}
          >
            문서 보기
          </Link>
        ) : (
          '—'
        )}
      </td>
      <td>{new Date(transaction.created_at).toLocaleString('ko-KR')}</td>
    </tr>
  )
}

/** 최근 거래 50건 표. 계약이 거래를 최신순으로 담아 준다(정렬을 다시 하지 않는다). */
function CreditTransactionsTable({ transactions }: { transactions: CreditTransaction[] }) {
  return (
    <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
      <table className="usage-table">
        <caption>최근 크레딧 거래 내역입니다.</caption>
        <thead>
          <tr>
            <th scope="col">종류</th>
            <th scope="col">크레딧</th>
            <th scope="col">사유</th>
            <th scope="col">메모</th>
            <th scope="col">문서</th>
            <th scope="col">일시</th>
          </tr>
        </thead>
        <tbody>
          {transactions.length === 0 ? (
            <tr>
              <td colSpan={6} className="text-muted-foreground">
                아직 거래가 없습니다.
              </td>
            </tr>
          ) : (
            transactions.map((transaction) => (
              <CreditTransactionRow key={transaction.id} transaction={transaction} />
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}

/** 세금계산서 요청 상태를 사람이 읽는 말로. `KIND_LABEL`과 같은 이유로 `Record<string, string>`이다. */
const INVOICE_STATUS_LABEL: Record<string, string> = {
  requested: '요청됨',
  issued: '발급됨',
  rejected: '거절됨',
}

/** 세금계산서 요청 폼이 관리하는 입력 값. */
interface InvoiceFormState {
  businessNumber: string
  companyName: string
  representativeName: string
  contactEmail: string
  address: string
  periodFrom: string
  periodTo: string
}

/** 기본값 — 기간은 지난달(§4 요구 그대로)이다. */
function defaultInvoiceFormState(): InvoiceFormState {
  const { from, to } = lastMonthRange(new Date())
  return {
    businessNumber: '',
    companyName: '',
    representativeName: '',
    contactEmail: '',
    address: '',
    periodFrom: from,
    periodTo: to,
  }
}

/**
 * 세금계산서 요청 인라인 폼. 성공하면 [onSuccess]를 부르고(목록 갱신은 호출부 몫), 실패하면
 * `role=alert`로 서버 문구를 그대로 보여준다.
 */
function InvoiceRequestForm({
  workspaceId,
  onSuccess,
  onCancel,
}: {
  workspaceId: string
  onSuccess: (created: InvoiceRequestResponse) => void
  onCancel: () => void
}) {
  const [form, setForm] = useState<InvoiceFormState>(defaultInvoiceFormState)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const businessNumberId = useId()
  const companyNameId = useId()
  const representativeNameId = useId()
  const contactEmailId = useId()
  const addressId = useId()
  const periodFromId = useId()
  const periodToId = useId()

  function updateField(field: keyof InvoiceFormState) {
    return (event: { target: { value: string } }) =>
      setForm((current) => ({ ...current, [field]: event.target.value }))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    const request: InvoiceRequestCreate = {
      business_number: form.businessNumber,
      company_name: form.companyName,
      representative_name: form.representativeName.trim() === '' ? null : form.representativeName,
      contact_email: form.contactEmail,
      address: form.address.trim() === '' ? null : form.address,
      period_from: form.periodFrom,
      period_to: form.periodTo,
    }
    try {
      const created = await createInvoiceRequest(workspaceId, request)
      onSuccess(created)
    } catch (caught: unknown) {
      setError(caught instanceof ApiError ? caught.message : INVOICE_SUBMIT_ERROR_MESSAGE)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      className="flex flex-col gap-4 rounded-[12px] border border-border bg-card p-4"
      aria-labelledby="invoice-request-form-heading"
      onSubmit={(event) => {
        void handleSubmit(event)
      }}
    >
      <h3 id="invoice-request-form-heading" className="text-[15px] font-semibold text-foreground">
        세금계산서 요청
      </h3>

      {error !== null && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      <div className="field">
        <label htmlFor={businessNumberId}>사업자등록번호</label>
        <input
          id={businessNumberId}
          type="text"
          inputMode="numeric"
          required
          placeholder="000-00-00000"
          value={form.businessNumber}
          onChange={updateField('businessNumber')}
        />
      </div>

      <div className="field">
        <label htmlFor={companyNameId}>상호</label>
        <input
          id={companyNameId}
          type="text"
          required
          value={form.companyName}
          onChange={updateField('companyName')}
        />
      </div>

      <div className="field">
        <label htmlFor={representativeNameId}>대표자명</label>
        <input
          id={representativeNameId}
          type="text"
          value={form.representativeName}
          onChange={updateField('representativeName')}
        />
      </div>

      <div className="field">
        <label htmlFor={contactEmailId}>연락 이메일</label>
        <input
          id={contactEmailId}
          type="email"
          required
          value={form.contactEmail}
          onChange={updateField('contactEmail')}
        />
      </div>

      <div className="field">
        <label htmlFor={addressId}>주소</label>
        <input id={addressId} type="text" value={form.address} onChange={updateField('address')} />
      </div>

      <div className="flex flex-wrap gap-4">
        <div className="field">
          <label htmlFor={periodFromId}>기간 시작일</label>
          <input
            id={periodFromId}
            type="date"
            required
            value={form.periodFrom}
            onChange={updateField('periodFrom')}
          />
        </div>
        <div className="field">
          <label htmlFor={periodToId}>기간 종료일</label>
          <input
            id={periodToId}
            type="date"
            required
            value={form.periodTo}
            onChange={updateField('periodTo')}
          />
        </div>
      </div>

      <div className="flex gap-3">
        <Button type="submit" loading={submitting}>
          {submitting ? '요청하는 중…' : '요청 보내기'}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel} disabled={submitting}>
          취소
        </Button>
      </div>
    </form>
  )
}

/** 요청 목록 표. 요청이 없으면 안내 문구를 낸다. */
function InvoiceRequestsTable({ items }: { items: InvoiceRequestResponse[] }) {
  return (
    <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
      <table className="usage-table">
        <caption>세금계산서 요청 목록입니다.</caption>
        <thead>
          <tr>
            <th scope="col">기간</th>
            <th scope="col">상호</th>
            <th scope="col">상태</th>
            <th scope="col">요청일</th>
            <th scope="col">운영자 메모</th>
          </tr>
        </thead>
        <tbody>
          {items.length === 0 ? (
            <tr>
              <td colSpan={5} className="text-muted-foreground">
                아직 요청이 없습니다.
              </td>
            </tr>
          ) : (
            items.map((item) => {
              const status: InvoiceRequestStatus | string = item.status
              return (
                <tr key={item.id}>
                  <td>
                    {item.period_from} ~ {item.period_to}
                  </td>
                  <th scope="row">{item.company_name}</th>
                  <td>{INVOICE_STATUS_LABEL[status] ?? status}</td>
                  <td>{new Date(item.requested_at).toLocaleString('ko-KR')}</td>
                  <td>{item.operator_note ?? '—'}</td>
                </tr>
              )
            })
          )}
        </tbody>
      </table>
    </div>
  )
}

/** 세금계산서 요청 접수 실패 시 서버 문구를 읽지 못했을 때 쓰는 문구. */
const INVOICE_SUBMIT_ERROR_MESSAGE =
  '세금계산서 요청을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 세금계산서 요청 목록을 불러오지 못했을 때 쓰는 문구. */
const INVOICE_LIST_LOAD_ERROR_MESSAGE =
  '세금계산서 요청 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 목적(purpose)을 사람이 읽는 말로 — `HistoryPage`의 `SOURCE_FORMAT_TEXT`와 같은 관례다.
 * `Record<string, string>`로 두는 이유도 같다: 계약이 `UsagePurpose`에 값을 늘리면 이
 * 맵이 그 값을 모를 수 있는데, `Record<UsagePurpose, string>`로 좁히면 타입 검사가
 * "이 맵은 항상 완전하다"고 보증해 버려 아래 `?? row.purpose` 대체 표시가 죽은 코드가
 * 된다 — 실제로는 배포 시차로 프런트가 새 값을 아직 모르는 순간이 있을 수 있다.
 */
const PURPOSE_LABEL: Record<string, string> = {
  convert: '변환',
  repair: '보정',
  reconvert: '재변환',
}

/** 기간 선택 갈래. `custom`은 두 날짜를 직접 입력하는 경우다. */
type PeriodMode = 'this-month' | 'last-month' | 'custom'

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** `<input type="date">`가 받는 형식이자 계약이 요구하는 `YYYY-MM-DD`다. */
function toDateInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 지난달 1일~말일. 브라우저 로캘 기준이라 서버 기본 시간대(`easydoc.usage.zone`)와
 * 자정 부근에서 하루 어긋날 수 있지만, 사람이 "지난달"이라고 부를 때 보는 기준은
 * 서버 설정이 아니라 자기 화면의 오늘이다.
 */
function lastMonthRange(today: Date): { from: string; to: string } {
  const from = new Date(today.getFullYear(), today.getMonth() - 1, 1)
  const to = new Date(today.getFullYear(), today.getMonth(), 0)
  return { from: toDateInput(from), to: toDateInput(to) }
}

/** 비용은 알려진 값이 없으면 `null`이다 — "0달러"와 "모른다"는 다른 사실이라 다르게 보여준다. */
function formatCostUsd(value: string | null): string {
  return value === null ? '모름' : `$${value}`
}

function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

type Period = { from?: string; to?: string } | null

/** 워크스페이스 + 기간을 하나의 문자열로 — 렌더 중 상태 조정에서 "바뀌었는가"만 재는 값. */
function fetchKey(workspaceId: string | null, period: Period): string {
  return `${workspaceId ?? ''}|${period === null ? '' : `${period.from ?? ''}~${period.to ?? ''}`}`
}

/** 기간 하나를 조회 파라미터로. 아직 값이 갖춰지지 않은 `custom`은 `null`이다. */
function periodParams(mode: PeriodMode, customFrom: string, customTo: string): Period {
  if (mode === 'this-month') {
    // 생략하면 서버가 이번 달 1일~오늘로 채운다(계약 기본값) — 여기서 다시 계산하지 않는다.
    return {}
  }
  if (mode === 'last-month') {
    return lastMonthRange(new Date())
  }
  return customFrom !== '' && customTo !== '' ? { from: customFrom, to: customTo } : null
}

/** 목적별 표. 그 기간에 호출이 없던 목적은 행이 없다(계약 — 0행을 채우지 않는다). */
/**
 * 목적별 표는 어느 한 행이라도 `failed_calls > 0`이면(계약 2.26.0) 「실패 호출」 열을
 * 낸다 — `TotalsTable`과 같은 규칙이다. 행마다 따로 켜고 끄면 열 수가 행마다 달라져
 * 표 모양이 어긋난다.
 */
function PurposeTable({ rows }: { rows: PurposeUsageItem[] }) {
  const showFailed = rows.some((row) => row.failed_calls > 0)
  return (
    <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
      <table className="usage-table">
        <caption>목적(변환·보정·재변환)별 집계입니다.</caption>
        <thead>
          <tr>
            <th scope="col">목적</th>
            <th scope="col">호출</th>
            {showFailed && <th scope="col">실패 호출</th>}
            <th scope="col">입력 토큰</th>
            <th scope="col">출력 토큰</th>
            <th scope="col">예상 비용(USD)</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={showFailed ? 6 : 5} className="text-muted-foreground">
                이 기간에 호출이 없습니다.
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={row.purpose}>
                <th scope="row">{PURPOSE_LABEL[row.purpose] ?? row.purpose}</th>
                <td>{formatCount(row.llm_calls)}</td>
                {showFailed && <td>{formatCount(row.failed_calls)}</td>}
                <td>{formatCount(row.input_tokens)}</td>
                <td>{formatCount(row.output_tokens)}</td>
                <td>{formatCostUsd(row.estimated_cost_usd)}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 합계 표. 비용 미상 건수·실패 호출 수는 0이면 아예 열을 만들지 않는다(0을 보여줘야
 * 할 만큼 중요하지 않다). 실패 호출(`failed_calls`, 계약 2.26.0)은 완성 자체가 나지
 * 않은 호출 수 — 위 문서·문자·크레딧·호출·토큰·비용에는 들어가지 않는다.
 */
function TotalsTable({ usage, caption }: { usage: WorkspaceUsageResponse; caption: string }) {
  const showUnknown = usage.cost_unknown_calls > 0
  const showFailed = usage.failed_calls > 0
  return (
    <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
      <table className="usage-table">
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">문서</th>
            <th scope="col">문자</th>
            {/* 원장(`llm_calls`)에서 유도한 「썼어야 할 크레딧」이다 — 계정 거래(`잔액` 카드,
            §6.3)와 다른 값일 수 있어 이름을 구분한다(계획 §6 리스크 3). */}
            <th scope="col">사용 크레딧</th>
            <th scope="col">호출</th>
            {showFailed && <th scope="col">실패 호출</th>}
            <th scope="col">입력 토큰</th>
            <th scope="col">출력 토큰</th>
            <th scope="col">예상 비용(USD)</th>
            {showUnknown && <th scope="col">비용 미상 건수</th>}
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{formatCount(usage.documents)}건</td>
            <td>{formatCount(usage.characters)}자</td>
            <td>{formatCount(usage.credits)}</td>
            <td>{formatCount(usage.llm_calls)}</td>
            {showFailed && <td>{formatCount(usage.failed_calls)}</td>}
            <td>{formatCount(usage.input_tokens)}</td>
            <td>{formatCount(usage.output_tokens)}</td>
            <td>{formatCostUsd(usage.estimated_cost_usd)}</td>
            {showUnknown && <td>{formatCount(usage.cost_unknown_calls)}</td>}
          </tr>
        </tbody>
      </table>
    </div>
  )
}

/**
 * 워크스페이스 사용량 화면 (U2, 계약 2.20.0).
 *
 * 기간은 이번 달(서버 기본값)·지난달·직접 입력 셋 중 하나다. 직접 입력은 두 날짜가
 * 모두 채워져야 조회한다 — 하나만 있는 상태로 보내면 서버가 그 값만으로 기간을
 * 판단하지 못해 422가 난다(다른 한쪽이 비었으니 이 화면이 먼저 막는다).
 */
export function UsagePage() {
  const { workspaces, currentId: workspaceId } = useWorkspace()
  const currentName = workspaces.find((workspace) => workspace.id === workspaceId)?.name ?? null

  const [mode, setMode] = useState<PeriodMode>('this-month')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [usage, setUsage] = useState<WorkspaceUsageResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 크레딧 계정은 기간과 무관하다(잔액은 지금 시점의 값이다) — 사용량 조회와 별도
  // 상태·별도 effect로 둔다. 기간을 바꿔도 다시 부르지 않는다.
  const [credits, setCredits] = useState<WorkspaceCreditsResponse | null>(null)
  const [creditsError, setCreditsError] = useState<string | null>(null)
  // 지연 초기값 — 워크스페이스가 하나도 없어 끝내 정해지지 않는 계정(workspaceId===null)
  // 이라면 애초에 조회가 나가지 않으므로 "불러오는 중"으로 시작하지 않는다.
  const [creditsLoading, setCreditsLoading] = useState(() => workspaceId !== null)

  // 세금계산서 요청도 크레딧 계정과 같은 이유로 기간과 무관한 별도 상태·별도 effect다.
  const [invoiceRequests, setInvoiceRequests] = useState<InvoiceRequestResponse[] | null>(null)
  const [invoiceRequestsError, setInvoiceRequestsError] = useState<string | null>(null)
  const [invoiceRequestsLoading, setInvoiceRequestsLoading] = useState(() => workspaceId !== null)
  const [showInvoiceForm, setShowInvoiceForm] = useState(false)
  const [invoiceStatusMessage, setInvoiceStatusMessage] = useState<string | null>(null)
  // 성공한 요청 뒤 목록을 다시 부르는 신호 — workspaceId가 그대로여도 이 값을 올리면
  // 아래 effect가 다시 돈다.
  const [invoiceReloadToken, setInvoiceReloadToken] = useState(0)

  const fromId = useId()
  const toId = useId()

  // 기간이 같으면 같은 참조를 유지한다 — 매 렌더 새 객체를 만들면 아래 effect가
  // 렌더마다 다시 돌아 조회를 반복한다.
  const period = useMemo(
    () => periodParams(mode, customFrom, customTo),
    [mode, customFrom, customTo],
  )

  // 조회를 다시 걸 대상(워크스페이스+기간)이 바뀌었는지 렌더 중에 잰다 — 워크스페이스
  // 전환은 이 화면 밖(WorkspaceMenu)에서 일어나 로컬 이벤트 핸들러가 없다. effect
  // 안에서 곧바로 setState를 부르는 대신 React 공식 "렌더 중 상태 조정" 패턴을 쓴다
  // (`HistoryPage`의 `renderedWorkspaceId`와 같은 이유).
  const key = fetchKey(workspaceId, period)
  const [renderedKey, setRenderedKey] = useState(key)
  if (renderedKey !== key) {
    setRenderedKey(key)
    // 이전 기간의 표를 새 기간을 기다리는 동안 그대로 보여주지 않는다 — 조회가 아직
    // 끝나지 않았는데 숫자가 남아 있으면 "직접 입력"으로 막 바꾼 순간에도 지난달 표가
    // 잠깐 새 기간의 답인 것처럼 보인다.
    setUsage(null)
    setError(null)
    if (workspaceId !== null && period !== null) {
      setLoading(true)
    }
  }

  // 워크스페이스가 바뀌면 이전 워크스페이스의 크레딧 카드를 그 자리에서 내린다 — 위
  // `renderedKey`와 같은 "렌더 중 상태 조정" 패턴이다.
  const [renderedCreditsWorkspaceId, setRenderedCreditsWorkspaceId] = useState(workspaceId)
  if (renderedCreditsWorkspaceId !== workspaceId) {
    setRenderedCreditsWorkspaceId(workspaceId)
    setCredits(null)
    setCreditsError(null)
    // 작업 공간이 없어지면(끝내 정해지지 않는 계정 포함) 조회 자체가 나가지 않으므로
    // 로딩 표시도 여기서 함께 끝낸다 — 아래 effect가 그 갈래에서 조기 반환하기 전에
    // 렌더 중에 미리 맞춘다("렌더 중 상태 조정" 패턴, effect 안에서 곧바로 setState를
    // 부르지 않는다).
    setCreditsLoading(workspaceId !== null)
  }

  // 워크스페이스가 바뀌면 이전 워크스페이스의 세금계산서 요청 상태도 그 자리에서 내린다 —
  // 위 `renderedCreditsWorkspaceId`와 같은 "렌더 중 상태 조정" 패턴이다.
  const [renderedInvoiceWorkspaceId, setRenderedInvoiceWorkspaceId] = useState(workspaceId)
  if (renderedInvoiceWorkspaceId !== workspaceId) {
    setRenderedInvoiceWorkspaceId(workspaceId)
    setInvoiceRequests(null)
    setInvoiceRequestsError(null)
    setInvoiceRequestsLoading(workspaceId !== null)
    setShowInvoiceForm(false)
    setInvoiceStatusMessage(null)
  }

  useEffect(() => {
    if (workspaceId === null) {
      return
    }
    const controller = new AbortController()
    getWorkspaceCredits(workspaceId, controller.signal)
      .then((response) => {
        setCredits(response)
        setCreditsError(null)
      })
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setCreditsError(caught instanceof ApiError ? caught.message : CREDITS_LOAD_ERROR_MESSAGE)
      })
      .finally(() => setCreditsLoading(false))
    return () => controller.abort()
  }, [workspaceId])

  useEffect(() => {
    if (workspaceId === null) {
      return
    }
    const controller = new AbortController()
    listInvoiceRequests(workspaceId, controller.signal)
      .then((response) => {
        setInvoiceRequests(response.items)
        setInvoiceRequestsError(null)
      })
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setInvoiceRequestsError(
          caught instanceof ApiError ? caught.message : INVOICE_LIST_LOAD_ERROR_MESSAGE,
        )
      })
      .finally(() => setInvoiceRequestsLoading(false))
    return () => controller.abort()
  }, [workspaceId, invoiceReloadToken])

  function handleInvoiceRequestCreated(created: InvoiceRequestResponse) {
    setShowInvoiceForm(false)
    setInvoiceStatusMessage(
      `세금계산서 요청을 접수했습니다 (${created.period_from} ~ ${created.period_to}).`,
    )
    setInvoiceReloadToken((token) => token + 1)
  }

  useEffect(() => {
    if (workspaceId === null || period === null) {
      return
    }
    const controller = new AbortController()
    getWorkspaceUsage(workspaceId, period, controller.signal)
      .then((response) => {
        setUsage(response)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setError(caught instanceof ApiError ? caught.message : LOAD_ERROR_MESSAGE)
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [workspaceId, period])

  const totalsCaption =
    currentName === null
      ? '이 기간 사용량 합계입니다.'
      : `‘${currentName}’의 이 기간 사용량 합계입니다.`

  return (
    <section aria-labelledby="usage-heading">
      <PageHeader
        context={currentName === null ? '사용량' : `${currentName} · 사용량`}
        title="워크스페이스 사용량을 확인합니다"
        description={
          currentName === null
            ? '문서·문자·크레딧·LLM 호출과 예상 비용을 기간별로 봅니다.'
            : `‘${currentName}’의 문서·문자·크레딧·LLM 호출과 예상 비용을 기간별로 봅니다.`
        }
        titleId="usage-heading"
      />

      <div className="flex flex-col gap-6">
        <fieldset className="flex flex-col gap-3 rounded-[12px] border border-border bg-card p-4">
          <legend className="px-1 text-[15px] font-semibold text-foreground">조회 기간</legend>
          <div className="flex flex-wrap gap-4">
            <label className="flex min-h-11 cursor-pointer items-center gap-2 font-medium">
              <input
                className="accent-primary"
                type="radio"
                name="usage-period"
                value="this-month"
                checked={mode === 'this-month'}
                onChange={() => setMode('this-month')}
              />
              이번 달
            </label>
            <label className="flex min-h-11 cursor-pointer items-center gap-2 font-medium">
              <input
                className="accent-primary"
                type="radio"
                name="usage-period"
                value="last-month"
                checked={mode === 'last-month'}
                onChange={() => setMode('last-month')}
              />
              지난달
            </label>
            <label className="flex min-h-11 cursor-pointer items-center gap-2 font-medium">
              <input
                className="accent-primary"
                type="radio"
                name="usage-period"
                value="custom"
                checked={mode === 'custom'}
                onChange={() => setMode('custom')}
              />
              직접 입력
            </label>
          </div>
          {mode === 'custom' && (
            <div className="flex flex-wrap gap-4">
              <div className="field">
                <label htmlFor={fromId}>시작일</label>
                <input
                  id={fromId}
                  type="date"
                  value={customFrom}
                  onChange={(event) => setCustomFrom(event.target.value)}
                />
              </div>
              <div className="field">
                <label htmlFor={toId}>종료일</label>
                <input
                  id={toId}
                  type="date"
                  value={customTo}
                  onChange={(event) => setCustomTo(event.target.value)}
                />
              </div>
            </div>
          )}
        </fieldset>

        {creditsError !== null && (
          <p className="form-error" role="alert">
            {creditsError}
          </p>
        )}

        {creditsLoading && (
          <p className="py-6 text-center text-sm text-primary" role="status">
            크레딧을 불러오는 중입니다…
          </p>
        )}

        {!creditsLoading && creditsError === null && credits !== null && (
          <>
            <CreditsCard credits={credits} />
            <CreditTransactionsTable transactions={credits.transactions} />
          </>
        )}

        <div className="flex flex-col gap-4">
          {!showInvoiceForm && workspaceId !== null && (
            <Button
              type="button"
              variant="outline"
              className="self-start"
              onClick={() => {
                setShowInvoiceForm(true)
                setInvoiceStatusMessage(null)
              }}
            >
              세금계산서 요청
            </Button>
          )}

          {invoiceStatusMessage !== null && (
            <p className="form-success" role="status">
              {invoiceStatusMessage}
            </p>
          )}

          {showInvoiceForm && workspaceId !== null && (
            <InvoiceRequestForm
              workspaceId={workspaceId}
              onSuccess={handleInvoiceRequestCreated}
              onCancel={() => setShowInvoiceForm(false)}
            />
          )}

          {invoiceRequestsError !== null && (
            <p className="form-error" role="alert">
              {invoiceRequestsError}
            </p>
          )}

          {invoiceRequestsLoading && (
            <p className="py-6 text-center text-sm text-primary" role="status">
              세금계산서 요청 목록을 불러오는 중입니다…
            </p>
          )}

          {!invoiceRequestsLoading && invoiceRequestsError === null && invoiceRequests !== null && (
            <InvoiceRequestsTable items={invoiceRequests} />
          )}
        </div>

        {error !== null && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        {loading && (
          <p className="py-6 text-center text-sm text-primary" role="status">
            불러오는 중입니다…
          </p>
        )}

        {!loading && error === null && usage !== null && (
          <>
            <TotalsTable usage={usage} caption={totalsCaption} />
            <PurposeTable rows={usage.by_purpose} />
          </>
        )}
      </div>
    </section>
  )
}
