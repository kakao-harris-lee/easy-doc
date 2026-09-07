import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../../api/client'
import {
  adjustAdminWorkspaceCredits,
  listAdminWorkspaces,
  readAdminWorkspace,
} from '../../api/admin'
import type {
  AdminCreditAdjustmentReason,
  AdminWorkspaceDetailResponse,
  AdminWorkspaceSummary,
} from '../../api/types'
import { Button } from '../../components/ui/Button'

/** 한 쪽에 담을 개수. 계약 기본값(20)과 같다. */
const PAGE_SIZE = 20

const LIST_ERROR_MESSAGE = '워크스페이스 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const DETAIL_ERROR_MESSAGE = '워크스페이스 상세를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const ADJUST_ERROR_MESSAGE = '크레딧을 조정하지 못했습니다. 잠시 후 다시 시도해 주세요.'

function formatCostUsd(value: string | null): string {
  return value === null ? '모름' : `$${value}`
}

function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

/** 크레딧 조정 폼이 관리하는 입력값. */
interface AdjustFormState {
  credits: string
  reason: AdminCreditAdjustmentReason
  note: string
}

function defaultAdjustFormState(): AdjustFormState {
  return { credits: '', reason: 'manual', note: '' }
}

/**
 * 선택한 워크스페이스의 상세 — 크레딧 요약·조정 폼·최근 거래·세금계산서 요청·최근 변환.
 *
 * 크레딧을 조정하면 이 패널을 다시 읽어 새 잔액을 보여준다. 목록(부모) 쪽 요약도
 * 낡으므로 `onCreditsAdjusted`로 부모에게 알려 목록도 다시 읽게 한다.
 */
function WorkspaceDetailPanel({
  workspaceId,
  onCreditsAdjusted,
}: {
  workspaceId: string
  onCreditsAdjusted: () => void
}) {
  const [detail, setDetail] = useState<AdminWorkspaceDetailResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const [form, setForm] = useState<AdjustFormState>(defaultAdjustFormState)
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  const headingId = useId()
  const creditsId = useId()
  const reasonId = useId()
  const noteId = useId()

  // 다른 워크스페이스를 선택하면 이전 상세를 그 자리에서 내린다("렌더 중 상태 조정"
  // 패턴, `UsagePage`·`HistoryPage`와 같은 이유).
  const [renderedWorkspaceId, setRenderedWorkspaceId] = useState(workspaceId)
  if (renderedWorkspaceId !== workspaceId) {
    setRenderedWorkspaceId(workspaceId)
    setDetail(null)
    setError(null)
    setLoading(true)
    setForm(defaultAdjustFormState())
    setFormError(null)
    setSuccessMessage(null)
  }

  useEffect(() => {
    const controller = new AbortController()
    readAdminWorkspace(workspaceId, controller.signal)
      .then((response) => {
        setDetail(response)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setError(caught instanceof ApiError ? caught.message : DETAIL_ERROR_MESSAGE)
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [workspaceId, reloadToken])

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setFormError(null)
    setSuccessMessage(null)
    const credits = Number(form.credits)
    if (!Number.isInteger(credits) || credits === 0) {
      setFormError('크레딧은 0이 아닌 정수여야 합니다.')
      return
    }
    setSubmitting(true)
    try {
      await adjustAdminWorkspaceCredits(workspaceId, {
        credits,
        reason: form.reason,
        note: form.note.trim() === '' ? null : form.note,
      })
      setSuccessMessage('크레딧을 조정했습니다.')
      setForm(defaultAdjustFormState())
      setReloadToken((token) => token + 1)
      onCreditsAdjusted()
    } catch (caught) {
      setFormError(caught instanceof ApiError ? caught.message : ADJUST_ERROR_MESSAGE)
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <p className="py-6 text-center text-sm text-primary" role="status">
        상세를 불러오는 중입니다…
      </p>
    )
  }

  if (error !== null) {
    return (
      <p className="form-error" role="alert">
        {error}
      </p>
    )
  }

  if (detail === null) {
    return null
  }

  return (
    // `div`에 aria-labelledby를 걸면 역할이 없어 낭독기가 그 참조를 아무 데도 쓰지
    // 않는다 — `section`이라야 region 랜드마크가 되어 이 제목이 실제 접근 가능한
    // 이름으로 쓰인다.
    <section
      className="flex flex-col gap-4 rounded-[12px] border border-border bg-card p-5"
      aria-labelledby={headingId}
    >
      <h3 id={headingId} className="text-[15px] font-semibold text-foreground">
        {detail.summary.name} 상세
      </h3>

      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div>
          <dt className="text-sm text-muted-foreground">가용</dt>
          <dd className="text-lg font-bold tabular-nums text-foreground">
            {formatCount(detail.summary.credit_available)}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">잔액</dt>
          <dd className="text-lg font-bold tabular-nums text-foreground">
            {formatCount(detail.summary.credit_balance)}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">예약 중</dt>
          <dd className="text-lg font-bold tabular-nums text-foreground">
            {formatCount(detail.summary.credit_reserved)}
          </dd>
        </div>
      </dl>

      <form
        className="flex flex-col gap-3 rounded-[10px] border border-border p-4"
        aria-label="크레딧 조정"
        onSubmit={(event) => void handleSubmit(event)}
      >
        <h4 className="text-sm font-semibold text-foreground">크레딧 조정</h4>

        {formError !== null && (
          <p className="form-error" role="alert">
            {formError}
          </p>
        )}
        {successMessage !== null && (
          <p className="form-success" role="status">
            {successMessage}
          </p>
        )}

        <div className="field">
          <label htmlFor={creditsId}>크레딧 (0이 될 수 없음)</label>
          <input
            id={creditsId}
            type="number"
            required
            value={form.credits}
            onChange={(event) =>
              setForm((current) => ({ ...current, credits: event.target.value }))
            }
          />
        </div>

        <div className="field">
          <label htmlFor={reasonId}>사유</label>
          <select
            id={reasonId}
            value={form.reason}
            onChange={(event) =>
              setForm((current) => ({
                ...current,
                reason: event.target.value as AdminCreditAdjustmentReason,
              }))
            }
          >
            <option value="plan_monthly">월 구독 갱신</option>
            <option value="manual">수동 부여</option>
            <option value="refund">환급</option>
          </select>
        </div>

        <div className="field">
          <label htmlFor={noteId}>메모 (선택)</label>
          <input
            id={noteId}
            type="text"
            maxLength={200}
            value={form.note}
            onChange={(event) => setForm((current) => ({ ...current, note: event.target.value }))}
          />
        </div>

        <Button type="submit" loading={submitting} className="self-start">
          {submitting ? '조정하는 중…' : '조정하기'}
        </Button>
      </form>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">최근 거래</h4>
        <table className="usage-table">
          <caption>최근 크레딧 거래 50건입니다.</caption>
          <thead>
            <tr>
              <th scope="col">종류</th>
              <th scope="col">크레딧</th>
              <th scope="col">사유</th>
              <th scope="col">일시</th>
            </tr>
          </thead>
          <tbody>
            {detail.transactions.length === 0 ? (
              <tr>
                <td colSpan={4} className="text-muted-foreground">
                  거래가 없습니다.
                </td>
              </tr>
            ) : (
              detail.transactions.map((transaction) => (
                <tr key={transaction.id}>
                  <th scope="row">{transaction.kind}</th>
                  <td className="tabular-nums">{transaction.credits}</td>
                  <td>{transaction.reason}</td>
                  <td>{new Date(transaction.created_at).toLocaleString('ko-KR')}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">세금계산서 요청</h4>
        <table className="usage-table">
          <caption>이 워크스페이스의 세금계산서 요청입니다.</caption>
          <thead>
            <tr>
              <th scope="col">상호</th>
              <th scope="col">상태</th>
              <th scope="col">요청일</th>
            </tr>
          </thead>
          <tbody>
            {detail.invoice_requests.length === 0 ? (
              <tr>
                <td colSpan={3} className="text-muted-foreground">
                  요청이 없습니다.
                </td>
              </tr>
            ) : (
              detail.invoice_requests.map((request) => (
                <tr key={request.id}>
                  <th scope="row">{request.company_name}</th>
                  <td>{request.status}</td>
                  <td>{new Date(request.requested_at).toLocaleString('ko-KR')}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">최근 변환</h4>
        {/* 본문·프롬프트는 계약이 애초에 실어 보내지 않는다(x-admin-only 노트) —
        여기서도 상태·실패 사유만 보여준다. */}
        <table className="usage-table">
          <caption>최근 변환 20건입니다. 본문은 담지 않습니다.</caption>
          <thead>
            <tr>
              <th scope="col">제목</th>
              <th scope="col">상태</th>
              <th scope="col">실패 사유</th>
              <th scope="col">일시</th>
            </tr>
          </thead>
          <tbody>
            {detail.recent_conversions.length === 0 ? (
              <tr>
                <td colSpan={4} className="text-muted-foreground">
                  변환 기록이 없습니다.
                </td>
              </tr>
            ) : (
              detail.recent_conversions.map((item) => (
                <tr key={item.id}>
                  <th scope="row">{item.title}</th>
                  <td>{item.status}</td>
                  <td>{item.failure_code ?? '—'}</td>
                  <td>{new Date(item.created_at).toLocaleString('ko-KR')}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/** 「워크스페이스」 탭 — 검색·목록·상세·크레딧 조정 (어드민 최소, 계약 2.25.0). */
export function AdminWorkspacesTab() {
  const [q, setQ] = useState('')
  const [appliedQ, setAppliedQ] = useState('')
  const [page, setPage] = useState(1)
  const [items, setItems] = useState<AdminWorkspaceSummary[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const searchId = useId()

  // 검색어·쪽·새로고침 신호가 바뀌었는지 렌더 중에 잰다("렌더 중 상태 조정" 패턴) —
  // effect 안에서 곧바로 setLoading(true)를 부르면 안 된다는 규칙을 지킨다.
  const queryKey = `${appliedQ}|${page}|${reloadToken}`
  const [renderedQueryKey, setRenderedQueryKey] = useState(queryKey)
  if (renderedQueryKey !== queryKey) {
    setRenderedQueryKey(queryKey)
    setLoading(true)
  }

  useEffect(() => {
    const controller = new AbortController()
    listAdminWorkspaces(
      { q: appliedQ === '' ? undefined : appliedQ, page, size: PAGE_SIZE },
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
  }, [appliedQ, page, reloadToken])

  function handleSearchSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    setPage(1)
    setAppliedQ(q.trim())
  }

  const hasMore = page * PAGE_SIZE < total

  return (
    <div className="flex flex-col gap-4">
      <form
        className="flex flex-wrap items-end gap-3"
        role="search"
        aria-label="워크스페이스 검색"
        onSubmit={handleSearchSubmit}
      >
        <div className="field">
          <label htmlFor={searchId}>이름·소유자 이메일 검색</label>
          <input
            id={searchId}
            type="search"
            value={q}
            onChange={(event) => setQ(event.target.value)}
          />
        </div>
        <Button type="submit">검색</Button>
      </form>

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
        <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
          <table className="usage-table">
            <caption>워크스페이스 목록입니다. 행을 누르면 상세가 열립니다.</caption>
            <thead>
              <tr>
                <th scope="col">이름</th>
                <th scope="col">소유자 이메일</th>
                <th scope="col">생성일</th>
                <th scope="col">가용/잔액/예약</th>
                <th scope="col">이번 달 문서/크레딧/비용</th>
              </tr>
            </thead>
            <tbody>
              {items.length === 0 ? (
                <tr>
                  <td colSpan={5} className="text-muted-foreground">
                    검색 결과가 없습니다.
                  </td>
                </tr>
              ) : (
                items.map((item) => (
                  <tr key={item.workspace_id}>
                    <th scope="row">
                      <button
                        type="button"
                        className="font-semibold text-primary underline-offset-4 hover:underline"
                        onClick={() => setSelectedId(item.workspace_id)}
                      >
                        {item.name}
                      </button>
                    </th>
                    <td>{item.owner_email}</td>
                    <td>{new Date(item.created_at).toLocaleDateString('ko-KR')}</td>
                    <td className="tabular-nums">
                      {formatCount(item.credit_available)}/{formatCount(item.credit_balance)}/
                      {formatCount(item.credit_reserved)}
                    </td>
                    <td className="tabular-nums">
                      {formatCount(item.month_documents)}/{formatCount(item.month_credits)}/
                      {formatCostUsd(item.month_cost_usd)}
                    </td>
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
      )}

      {selectedId !== null && (
        <WorkspaceDetailPanel
          workspaceId={selectedId}
          onCreditsAdjusted={() => setReloadToken((token) => token + 1)}
        />
      )}
    </div>
  )
}
