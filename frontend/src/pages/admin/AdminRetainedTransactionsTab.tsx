import { useEffect, useMemo, useState } from 'react'

import { ApiError } from '../../api/client'
import {
  getAdminRetainedTransaction,
  listAdminRetainedTransactions,
  postAdminComplaintDecision,
  postAdminRefundDecision,
} from '../../api/admin'
import type {
  AdminComplaintDecisionRequest,
  AdminRefundDecisionRequest,
  AdminRetainedTransactionDetail,
  AdminRetainedTransactionListResponse,
} from '../../api/types'
import { Button } from '../../components/ui/Button'
import { readOperation, saveOperation, useAdminOperationKey } from './adminOperationStorage'

const PAGE_SIZE = 20

function localDateTimeValue(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset()
  return new Date(now.getTime() - offset * 60_000).toISOString().slice(0, 16)
}

function localDateTimeFromIso(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return localDateTimeValue()
  const offset = parsed.getTimezoneOffset()
  return new Date(parsed.getTime() - offset * 60_000).toISOString().slice(0, 16)
}

function isoDateTime(value: string): string {
  return new Date(value).toISOString()
}

function dateFilterValue(value: string, endOfDay: boolean): string | undefined {
  if (value === '') return undefined
  const parsed = new Date(`${value}T${endOfDay ? '23:59:59.999' : '00:00:00'}`)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? cause.message : fallback
}

function hasUnknownResult(cause: unknown): boolean {
  return (
    !(cause instanceof ApiError) ||
    cause.status === 0 ||
    cause.status === 401 ||
    cause.status >= 500
  )
}

function formatDate(value: string | null, includeTime = true): string {
  if (value === null) return '기록 없음'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '기록 없음'
  return includeTime ? parsed.toLocaleString('ko-KR') : parsed.toLocaleDateString('ko-KR')
}

interface StoredRefundDecision {
  operation_id: string
  request: Omit<AdminRefundDecisionRequest, 'operation_id'>
}

interface StoredComplaintDecision {
  operation_id: string
  request: Omit<AdminComplaintDecisionRequest, 'operation_id'>
}

function RefundDecisionForm({
  transactionId,
  onSaved,
}: {
  transactionId: string
  onSaved: (detail: AdminRetainedTransactionDetail) => void
}) {
  const storageKey = useAdminOperationKey(transactionId, 'retained-refund-decision')
  const [stored] = useState<StoredRefundDecision | null>(() =>
    readOperation<StoredRefundDecision>(storageKey),
  )
  const [operationId, setOperationId] = useState(() => stored?.operation_id ?? crypto.randomUUID())
  const [pending, setPending] = useState(stored !== null)
  const [status, setStatus] = useState<AdminRefundDecisionRequest['status']>(
    () => stored?.request.status ?? 'approved',
  )
  const [amount, setAmount] = useState(() => String(stored?.request.amount ?? 0))
  const [recoveryCredits, setRecoveryCredits] = useState(() =>
    String(stored?.request.recovery_credits ?? 0),
  )
  const [reason, setReason] = useState(() => stored?.request.reason ?? '')
  const [stopRenewal, setStopRenewal] = useState(() => stored?.request.stop_renewal ?? false)
  const [receivedAt, setReceivedAt] = useState(() =>
    stored === null ? localDateTimeValue() : localDateTimeFromIso(stored.request.received_at),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(
    stored === null ? null : '확인되지 않은 판단이 있습니다. 같은 내용으로 결과를 다시 확인하세요.',
  )

  async function submit(): Promise<void> {
    const parsedAmount = Number(amount)
    const parsedCredits = Number(recoveryCredits)
    if (!Number.isFinite(parsedAmount) || parsedAmount < 0) {
      setError('환불 금액을 0 이상으로 입력해 주세요.')
      return
    }
    if (!Number.isInteger(parsedAmount)) {
      setError('환불 금액은 원 단위 정수로 입력해 주세요.')
      return
    }
    if (!Number.isFinite(parsedCredits) || parsedCredits < 0) {
      setError('회수 크레딧을 0 이상으로 입력해 주세요.')
      return
    }
    if (!Number.isInteger(parsedCredits * 10)) {
      setError('회수 크레딧은 0.1 단위로 입력해 주세요.')
      return
    }
    if (status === 'approved' && parsedAmount <= 0) {
      setError('승인된 환불 금액은 0보다 커야 합니다.')
      return
    }
    if (status === 'rejected' && parsedAmount !== 0) {
      setError('거절 판단의 환불 금액은 0이어야 합니다.')
      return
    }
    if (reason.trim() === '') {
      setError('판단 사유를 입력해 주세요.')
      return
    }
    let receivedIso: string
    try {
      receivedIso = isoDateTime(receivedAt)
    } catch {
      setError('접수 시각을 확인해 주세요.')
      return
    }
    const request: AdminRefundDecisionRequest = {
      operation_id: operationId,
      status,
      amount: status === 'rejected' ? 0 : parsedAmount,
      recovery_credits: parsedCredits,
      reason: reason.trim(),
      stop_renewal: stopRenewal,
      received_at: receivedIso,
    }
    saveOperation(storageKey, {
      operation_id: operationId,
      request: { ...request, operation_id: undefined },
    })
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      const detail = await postAdminRefundDecision(transactionId, request)
      saveOperation(storageKey, null)
      setPending(false)
      setOperationId(crypto.randomUUID())
      setMessage('환불 판단을 기록했습니다. 실제 환불 처리는 별도 결제 운영 작업입니다.')
      onSaved(detail)
    } catch (cause) {
      setError(errorMessage(cause, '판단 결과를 확인하지 못했습니다. 같은 요청을 다시 확인하세요.'))
      if (hasUnknownResult(cause)) {
        setPending(true)
        setMessage(
          '요청이 처리됐는지 확인되지 않았습니다. 입력을 바꾸지 말고 같은 판단을 다시 확인하세요.',
        )
      } else {
        // A validation, permission, or conflict response is a known result. It is safe to
        // discard this operation id and let the operator correct the form with a new one.
        saveOperation(storageKey, null)
        setPending(false)
        setOperationId(crypto.randomUUID())
        setMessage('서버가 판단을 기록하지 못했습니다. 내용을 확인한 뒤 다시 기록해 주세요.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className="space-y-3 rounded-lg border border-border p-3"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      <h3 className="font-semibold">환불 판단 기록</h3>
      <p className="text-xs text-muted-foreground">
        이 폼은 판단 기록만 남깁니다. 결제 환불과 크레딧 회수는 별도 운영 조치입니다.
      </p>
      <label className="block text-sm">
        판단
        <select
          className="mt-1 block w-full"
          value={status}
          disabled={busy || pending}
          onChange={(event) => {
            const next = event.target.value as AdminRefundDecisionRequest['status']
            setStatus(next)
            if (next === 'rejected') setAmount('0')
          }}
        >
          <option value="approved">승인</option>
          <option value="rejected">거절</option>
        </select>
      </label>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block text-sm">
          환불 금액(원)
          <input
            className="mt-1 block w-full"
            type="number"
            min="0"
            step="1"
            value={amount}
            disabled={busy || pending}
            onChange={(event) => setAmount(event.target.value)}
          />
        </label>
        <label className="block text-sm">
          회수 크레딧
          <input
            className="mt-1 block w-full"
            type="number"
            min="0"
            step="0.1"
            value={recoveryCredits}
            disabled={busy || pending}
            onChange={(event) => setRecoveryCredits(event.target.value)}
          />
        </label>
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={stopRenewal}
          disabled={busy || pending}
          onChange={(event) => setStopRenewal(event.target.checked)}
        />
        다음 갱신 중단
      </label>
      <label className="block text-sm">
        접수 시각
        <input
          className="mt-1 block w-full"
          type="datetime-local"
          value={receivedAt}
          disabled={busy || pending}
          onChange={(event) => setReceivedAt(event.target.value)}
        />
      </label>
      <label className="block text-sm">
        판단 사유
        <textarea
          className="mt-1 block min-h-20 w-full"
          maxLength={200}
          value={reason}
          disabled={busy || pending}
          onChange={(event) => setReason(event.target.value)}
        />
      </label>
      {error !== null && <p role="alert">{error}</p>}
      {message !== null && <p role="status">{message}</p>}
      <Button type="submit" disabled={busy} loading={busy}>
        {busy ? '기록 중…' : '환불 판단 기록'}
      </Button>
    </form>
  )
}

function ComplaintDecisionForm({
  transactionId,
  onSaved,
}: {
  transactionId: string
  onSaved: (detail: AdminRetainedTransactionDetail) => void
}) {
  const storageKey = useAdminOperationKey(transactionId, 'retained-complaint-decision')
  const [stored] = useState<StoredComplaintDecision | null>(() =>
    readOperation<StoredComplaintDecision>(storageKey),
  )
  const [operationId, setOperationId] = useState(() => stored?.operation_id ?? crypto.randomUUID())
  const [pending, setPending] = useState(stored !== null)
  const [status, setStatus] = useState<AdminComplaintDecisionRequest['status']>(
    () => stored?.request.status ?? 'resolved',
  )
  const [reason, setReason] = useState(() => stored?.request.reason ?? '')
  const [receivedAt, setReceivedAt] = useState(() =>
    stored === null ? localDateTimeValue() : localDateTimeFromIso(stored.request.received_at),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(
    stored === null ? null : '확인되지 않은 판단이 있습니다. 같은 내용으로 결과를 다시 확인하세요.',
  )

  async function submit(): Promise<void> {
    if (reason.trim() === '') {
      setError('판단 사유를 입력해 주세요.')
      return
    }
    let receivedIso: string
    try {
      receivedIso = isoDateTime(receivedAt)
    } catch {
      setError('접수 시각을 확인해 주세요.')
      return
    }
    const request: AdminComplaintDecisionRequest = {
      operation_id: operationId,
      status,
      reason: reason.trim(),
      received_at: receivedIso,
    }
    saveOperation(storageKey, {
      operation_id: operationId,
      request: { ...request, operation_id: undefined },
    })
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      const detail = await postAdminComplaintDecision(transactionId, request)
      saveOperation(storageKey, null)
      setPending(false)
      setOperationId(crypto.randomUUID())
      setMessage('불만 처리 판단을 기록했습니다.')
      onSaved(detail)
    } catch (cause) {
      setError(errorMessage(cause, '판단 결과를 확인하지 못했습니다. 같은 요청을 다시 확인하세요.'))
      if (hasUnknownResult(cause)) {
        setPending(true)
        setMessage(
          '요청이 처리됐는지 확인되지 않았습니다. 입력을 바꾸지 말고 같은 판단을 다시 확인하세요.',
        )
      } else {
        saveOperation(storageKey, null)
        setPending(false)
        setOperationId(crypto.randomUUID())
        setMessage('서버가 판단을 기록하지 못했습니다. 내용을 확인한 뒤 다시 기록해 주세요.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      className="space-y-3 rounded-lg border border-border p-3"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      <h3 className="font-semibold">소비자 불만 판단 기록</h3>
      <p className="text-xs text-muted-foreground">이 폼은 처리 판단만 기록합니다.</p>
      <label className="block text-sm">
        판단
        <select
          className="mt-1 block w-full"
          value={status}
          disabled={busy || pending}
          onChange={(event) =>
            setStatus(event.target.value as AdminComplaintDecisionRequest['status'])
          }
        >
          <option value="resolved">해결</option>
          <option value="rejected">거절</option>
        </select>
      </label>
      <label className="block text-sm">
        접수 시각
        <input
          className="mt-1 block w-full"
          type="datetime-local"
          value={receivedAt}
          disabled={busy || pending}
          onChange={(event) => setReceivedAt(event.target.value)}
        />
      </label>
      <label className="block text-sm">
        판단 사유
        <textarea
          className="mt-1 block min-h-20 w-full"
          maxLength={200}
          value={reason}
          disabled={busy || pending}
          onChange={(event) => setReason(event.target.value)}
        />
      </label>
      {error !== null && <p role="alert">{error}</p>}
      {message !== null && <p role="status">{message}</p>}
      <Button type="submit" disabled={busy} loading={busy}>
        {busy ? '기록 중…' : '불만 판단 기록'}
      </Button>
    </form>
  )
}

export function AdminRetainedTransactionsTab() {
  const [filters, setFilters] = useState({ type: '', from: '', to: '' })
  const [applied, setApplied] = useState(filters)
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<AdminRetainedTransactionListResponse | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<AdminRetainedTransactionDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [detailError, setDetailError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    void listAdminRetainedTransactions(
      {
        // The API page is zero based; the visible page number stays one based for operators.
        page: page - 1,
        size: PAGE_SIZE,
        type: applied.type,
        from: dateFilterValue(applied.from, false),
        to: dateFilterValue(applied.to, true),
      },
      controller.signal,
    )
      .then((data) => {
        if (!controller.signal.aborted) {
          setResult(data)
          setError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setError(errorMessage(cause, '보존 거래를 불러오지 못했습니다.'))
      })
    return () => controller.abort()
  }, [page, applied])

  useEffect(() => {
    if (selectedId === null) {
      return
    }
    const controller = new AbortController()
    void getAdminRetainedTransaction(selectedId, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) {
          setDetail(data)
          setDetailError(null)
        }
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setDetailError(errorMessage(cause, '보존 거래 상세를 불러오지 못했습니다.'))
      })
    return () => controller.abort()
  }, [selectedId])

  const totalPages = useMemo(
    () => Math.max(1, Math.ceil((result?.total ?? 0) / (result?.size || PAGE_SIZE))),
    [result],
  )

  function applyFilters(): void {
    setPage(1)
    setApplied({ ...filters })
  }

  return (
    <section aria-label="보존 거래 관리" className="space-y-4">
      <div className="rounded-xl border border-border p-4">
        <h2 className="font-semibold">보존 거래</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          법령상 보존하는 거래·불만 기록의 메타데이터를 조회하고 판단을 기록합니다.
        </p>
        <form
          className="mt-3 grid gap-3 sm:grid-cols-4"
          onSubmit={(event) => {
            event.preventDefault()
            applyFilters()
          }}
        >
          <label className="text-sm">
            종류
            <input
              className="mt-1 block w-full"
              value={filters.type}
              onChange={(event) =>
                setFilters((current) => ({ ...current, type: event.target.value }))
              }
            />
          </label>
          <label className="text-sm">
            시작일
            <input
              className="mt-1 block w-full"
              type="date"
              value={filters.from}
              onChange={(event) =>
                setFilters((current) => ({ ...current, from: event.target.value }))
              }
            />
          </label>
          <label className="text-sm">
            종료일
            <input
              className="mt-1 block w-full"
              type="date"
              value={filters.to}
              onChange={(event) =>
                setFilters((current) => ({ ...current, to: event.target.value }))
              }
            />
          </label>
          <Button type="submit" className="self-end">
            조회
          </Button>
        </form>
      </div>

      {error !== null && <p role="alert">{error}</p>}
      {result === null ? (
        <p role="status">보존 거래를 불러오는 중…</p>
      ) : result.items.length === 0 ? (
        <p>조건에 맞는 보존 거래가 없습니다.</p>
      ) : (
        <>
          <ul className="divide-y divide-border rounded-xl border border-border">
            {result.items.map((item) => (
              <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 p-3">
                <div className="text-sm">
                  <p className="font-medium">
                    {item.type} · {item.environment ?? '환경 미상'}
                  </p>
                  {item.manual_review === true && (
                    <p className="font-medium text-danger">증빙 확인 필요</p>
                  )}
                  <p className="text-muted-foreground">
                    발생 {formatDate(item.occurred_at)} · 보존 만료{' '}
                    {formatDate(item.retained_until, false)}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    결제 {item.payment_id ?? '식별자 미상'}
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    if (selectedId === item.id) return
                    setDetail(null)
                    setDetailError(null)
                    setSelectedId(item.id)
                  }}
                >
                  상세 보기
                </Button>
              </li>
            ))}
          </ul>
          <div className="flex items-center justify-between text-sm">
            <span>
              {result.total}건 · {page}/{totalPages}쪽
            </span>
            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                disabled={page <= 1}
                onClick={() => setPage((current) => Math.max(1, current - 1))}
              >
                이전
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={page >= totalPages}
                onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
              >
                다음
              </Button>
            </div>
          </div>
        </>
      )}

      {selectedId !== null && (
        <section
          aria-label="보존 거래 상세"
          className="space-y-4 rounded-xl border border-border p-4"
        >
          <div className="flex items-start justify-between gap-3">
            <h2 className="font-semibold">보존 거래 상세</h2>
            <Button type="button" variant="ghost" onClick={() => setSelectedId(null)}>
              닫기
            </Button>
          </div>
          {detailError !== null && <p role="alert">{detailError}</p>}
          {detail === null ? (
            !detailError && <p role="status">상세를 불러오는 중…</p>
          ) : (
            <>
              {detail.manual_review === true && (
                <p role="alert" className="rounded-md border border-danger p-3 text-sm">
                  증빙 확인 필요: 보존된 자료가 부족해 운영자가 거래 기록을 확인해야 합니다.
                </p>
              )}
              <dl className="grid gap-2 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-muted-foreground">종류</dt>
                  <dd>{detail.type}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">환경</dt>
                  <dd>{detail.environment ?? '환경 미상'}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">결제 식별자</dt>
                  <dd>{detail.payment_id ?? '식별자 미상'}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">보존 만료</dt>
                  <dd>{formatDate(detail.retained_until)}</dd>
                </div>
              </dl>
              <details>
                <summary className="cursor-pointer text-sm">선별된 기록 내용</summary>
                <pre className="mt-2 max-h-80 overflow-auto rounded bg-muted p-3 text-xs">
                  {JSON.stringify(detail.payload, null, 2)}
                </pre>
              </details>
              {detail.decisions !== undefined && (
                <details>
                  <summary className="cursor-pointer text-sm">
                    판단 기록 {detail.decisions.length}건
                  </summary>
                  <pre className="mt-2 max-h-80 overflow-auto rounded bg-muted p-3 text-xs">
                    {JSON.stringify(detail.decisions, null, 2)}
                  </pre>
                </details>
              )}
              <div className="grid gap-4 lg:grid-cols-2">
                <RefundDecisionForm
                  key={`refund:${detail.id}`}
                  transactionId={detail.id}
                  onSaved={(updated) =>
                    setDetail((current) => (current?.id === updated.id ? updated : current))
                  }
                />
                <ComplaintDecisionForm
                  key={`complaint:${detail.id}`}
                  transactionId={detail.id}
                  onSaved={(updated) =>
                    setDetail((current) => (current?.id === updated.id ? updated : current))
                  }
                />
              </div>
            </>
          )}
        </section>
      )}
    </section>
  )
}
