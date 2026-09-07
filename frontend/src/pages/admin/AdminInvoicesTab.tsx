import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../../api/client'
import { handleAdminInvoiceRequest, listAdminInvoiceRequests } from '../../api/admin'
import type { InvoiceRequestResponse, InvoiceRequestStatus } from '../../api/types'
import { Button } from '../../components/ui/Button'

const PAGE_SIZE = 20

const LIST_ERROR_MESSAGE = '세금계산서 요청 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const HANDLE_ERROR_MESSAGE = '세금계산서 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 상태 필터 값 — `undefined`는 「전체」다(계약 `status` 파라미터 생략과 같다). */
type StatusFilter = InvoiceRequestStatus | 'all'

const STATUS_LABEL: Record<string, string> = {
  requested: '요청됨',
  issued: '발급됨',
  rejected: '거절됨',
}

/** 처리 폼이 관리하는 입력값. */
interface HandleFormState {
  status: 'issued' | 'rejected'
  note: string
}

function defaultHandleFormState(): HandleFormState {
  return { status: 'issued', note: '' }
}

/**
 * 요청 한 건의 처리 폼 — 발급·거절 + 메모.
 *
 * 이미 처리된 요청(`requested`가 아닌 요청)에는 폼을 그리지 않는다 — 서버가 409로
 * 거절할 조작을 화면이 먼저 걸러 헛된 왕복을 줄인다.
 */
function HandleForm({
  request,
  onHandled,
}: {
  request: InvoiceRequestResponse
  onHandled: (handled: InvoiceRequestResponse) => void
}) {
  const [form, setForm] = useState<HandleFormState>(defaultHandleFormState)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const statusId = useId()
  const noteId = useId()

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const handled = await handleAdminInvoiceRequest(request.id, {
        status: form.status,
        note: form.note.trim() === '' ? null : form.note,
      })
      onHandled(handled)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : HANDLE_ERROR_MESSAGE)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      className="flex flex-wrap items-end gap-3 rounded-[10px] border border-border p-3"
      // 같은 상호로 기간이 다른 요청이 여러 건 나란히 나올 수 있다 — 상호만으로는
      // 어떤 요청의 폼인지 구분되지 않으므로 기간을 이름에 싣는다.
      aria-label={`${request.company_name} 요청 처리 (${request.period_from}~${request.period_to})`}
      onSubmit={(event) => void handleSubmit(event)}
    >
      {error !== null && (
        <p className="form-error basis-full" role="alert">
          {error}
        </p>
      )}

      <div className="field">
        <label htmlFor={statusId}>처리 결과</label>
        <select
          id={statusId}
          value={form.status}
          onChange={(event) =>
            setForm((current) => ({
              ...current,
              status: event.target.value as HandleFormState['status'],
            }))
          }
        >
          <option value="issued">발급</option>
          <option value="rejected">거절</option>
        </select>
      </div>

      <div className="field">
        <label htmlFor={noteId}>메모 (선택)</label>
        <input
          id={noteId}
          type="text"
          maxLength={500}
          value={form.note}
          onChange={(event) => setForm((current) => ({ ...current, note: event.target.value }))}
        />
      </div>

      <Button type="submit" loading={submitting}>
        {submitting ? '처리하는 중…' : '처리하기'}
      </Button>
    </form>
  )
}

/** 「세금계산서」 탭 — 상태 필터·목록·처리 (어드민 최소, 계약 2.25.0). */
export function AdminInvoicesTab() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('requested')
  const [page, setPage] = useState(1)
  const [items, setItems] = useState<InvoiceRequestResponse[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const filterId = useId()

  // 필터·쪽·새로고침 신호가 바뀌었는지 렌더 중에 잰다("렌더 중 상태 조정" 패턴) —
  // effect 안에서 곧바로 setLoading(true)를 부르면 안 된다는 규칙을 지킨다.
  const queryKey = `${statusFilter}|${page}|${reloadToken}`
  const [renderedQueryKey, setRenderedQueryKey] = useState(queryKey)
  if (renderedQueryKey !== queryKey) {
    setRenderedQueryKey(queryKey)
    setLoading(true)
  }

  useEffect(() => {
    const controller = new AbortController()
    listAdminInvoiceRequests(
      { status: statusFilter === 'all' ? undefined : statusFilter, page, size: PAGE_SIZE },
      controller.signal,
    )
      .then((response) => {
        setItems(response.items)
        setTotal(response.total)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setError(caught instanceof ApiError ? caught.message : LIST_ERROR_MESSAGE)
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [statusFilter, page, reloadToken])

  function handleHandled(handled: InvoiceRequestResponse): void {
    setStatusMessage(
      `‘${handled.company_name}’ 요청을 ${STATUS_LABEL[handled.status] ?? handled.status} 처리했습니다.`,
    )
    setReloadToken((token) => token + 1)
  }

  const hasMore = page * PAGE_SIZE < total

  return (
    <div className="flex flex-col gap-4">
      <div className="field max-w-xs">
        <label htmlFor={filterId}>상태</label>
        <select
          id={filterId}
          value={statusFilter}
          onChange={(event) => {
            setPage(1)
            setStatusMessage(null)
            setStatusFilter(event.target.value as StatusFilter)
          }}
        >
          <option value="requested">요청됨</option>
          <option value="issued">발급됨</option>
          <option value="rejected">거절됨</option>
          <option value="all">전체</option>
        </select>
      </div>

      {statusMessage !== null && (
        <p className="form-success" role="status">
          {statusMessage}
        </p>
      )}

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

      {!loading && error === null && (
        <div className="flex flex-col gap-4">
          <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
            <table className="usage-table">
              <caption>세금계산서 요청 목록입니다.</caption>
              <thead>
                <tr>
                  <th scope="col">상호</th>
                  <th scope="col">사업자번호</th>
                  <th scope="col">기간</th>
                  <th scope="col">상태</th>
                  <th scope="col">요청일</th>
                </tr>
              </thead>
              <tbody>
                {items.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="text-muted-foreground">
                      요청이 없습니다.
                    </td>
                  </tr>
                ) : (
                  items.map((item) => (
                    <tr key={item.id}>
                      <th scope="row">{item.company_name}</th>
                      <td>{item.business_number}</td>
                      <td>
                        {item.period_from} ~ {item.period_to}
                      </td>
                      <td>{STATUS_LABEL[item.status] ?? item.status}</td>
                      <td>{new Date(item.requested_at).toLocaleString('ko-KR')}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>

            <div className="mt-4 flex items-center justify-between gap-3">
              <Button
                type="button"
                variant="outline"
                onClick={() => setPage((current) => Math.max(1, current - 1))}
                disabled={page <= 1}
              >
                이전
              </Button>
              <span className="text-sm text-muted-foreground">{page}쪽</span>
              <Button
                type="button"
                variant="outline"
                onClick={() => setPage((current) => current + 1)}
                disabled={!hasMore}
              >
                다음
              </Button>
            </div>
          </div>

          {/* 처리 대기(`requested`)만 폼을 낸다 — 발급·거절이 끝난 요청은 다시 처리할
          수 없다(서버 409). */}
          {items
            .filter((item) => item.status === 'requested')
            .map((item) => (
              <HandleForm key={item.id} request={item} onHandled={handleHandled} />
            ))}
        </div>
      )}
    </div>
  )
}
