import { useEffect, useId, useState, useCallback } from 'react'
import type { FormEvent } from 'react'

import { useAdminQuery, adminPageNumber } from './useAdminQuery'
import { ApiError } from '../../api/client'
import { listAdminWorkspaces, readAdminWorkspace } from '../../api/admin'
import type { AdminWorkspaceDetailResponse, AdminWorkspaceSummary } from '../../api/types'
import { AdminPaymentSummary } from './AdminPaymentSummary'
import { AdminMonthlyPanel } from './AdminMonthlyPanel'
import { currentMonth, SERVICE_START_MONTH, shiftMonth, validMonth } from './adminMonths'
import { SubscriptionCard } from '../../components/subscription/SubscriptionCard'
import { Button } from '../../components/ui/Button'
import { formatCredits } from '../../lib/credits'

/** 한 쪽에 담을 개수. 계약 기본값(20)과 같다. */
const PAGE_SIZE = 20

const LIST_ERROR_MESSAGE = '워크스페이스 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const DETAIL_ERROR_MESSAGE = '워크스페이스 상세를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 한 계정(소유자 이메일)이 이 쪽에서 가진 작업 공간들. */
interface OwnerGroup {
  ownerEmail: string
  workspaces: [AdminWorkspaceSummary, ...AdminWorkspaceSummary[]]
}

/**
 * 목록을 소유자 이메일로 묶는다 — 계정이 처음 나온 순서를 지킨다.
 *
 * 묶음은 「지금 불러온 쪽」 안에서만 성립한다. 서버가 워크스페이스를 생성일 내림차순으로
 * 늘어놓고 쪽을 나누므로, 한 계정의 작업 공간은 이웃하지 않은 여러 쪽에 흩어져 나올 수
 * 있다 — 한 계정 것을 한자리에서 보려면 그 계정의 이메일로 검색한다(한 쪽에 20개).
 */
function groupByOwner(items: AdminWorkspaceSummary[]): OwnerGroup[] {
  const groups: OwnerGroup[] = []
  const byOwner = new Map<string, OwnerGroup>()
  for (const item of items) {
    const found = byOwner.get(item.owner_email)
    if (found === undefined) {
      const group: OwnerGroup = { ownerEmail: item.owner_email, workspaces: [item] }
      byOwner.set(item.owner_email, group)
      groups.push(group)
    } else {
      found.workspaces.push(item)
    }
  }
  return groups
}

/**
 * 선택한 워크스페이스의 상세 — 크레딧 요약·부여 폼·최근 거래·세금계산서 요청·최근 변환.
 *
 * 크레딧을 조정하면 이 패널을 다시 읽어 새 잔액을 보여준다. 목록(부모) 쪽 요약도
 * 낡으므로 `onCreditsAdjusted`로 부모에게 알려 목록도 다시 읽게 한다.
 */
function WorkspaceDetailPanel({
  workspaceId,
  month,
  onMonthChange,
  onCreditsAdjusted,
}: {
  workspaceId: string
  month: string
  onMonthChange: (month: string) => void
  onCreditsAdjusted: () => void
}) {
  const [detail, setDetail] = useState<AdminWorkspaceDetailResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const headingId = useId()
  // 다른 워크스페이스를 선택하면 이전 상세를 그 자리에서 내린다("렌더 중 상태 조정"
  // 패턴, `UsagePage`·`HistoryPage`와 같은 이유).
  const [renderedWorkspaceId, setRenderedWorkspaceId] = useState(workspaceId)
  if (renderedWorkspaceId !== workspaceId) {
    setRenderedWorkspaceId(workspaceId)
    setDetail(null)
    setError(null)
    setLoading(true)
  }

  useEffect(() => {
    const controller = new AbortController()
    readAdminWorkspace(workspaceId, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return
        setDetail(response)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setError(caught instanceof ApiError ? caught.message : DETAIL_ERROR_MESSAGE)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [workspaceId, reloadToken])

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
        {detail.summary.owner_email} · {detail.summary.name} 상세
      </h3>

      <AdminMonthlyPanel
        key={workspaceId}
        workspaceId={workspaceId}
        refreshToken={reloadToken}
        month={month}
        onMonthChange={onMonthChange}
        onChanged={() => {
          setReloadToken((token) => token + 1)
          onCreditsAdjusted()
        }}
      />
      <SubscriptionCard
        key={`${workspaceId}:${reloadToken}`}
        workspaceId={workspaceId}
        admin
        hidePayments
      />
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

/** 「워크스페이스」 탭 — 사용자 검색·목록·상세·크레딧 부여 (어드민 최소, 계약 2.25.0). */
export function AdminWorkspacesTab() {
  const [initialQuery, updateQuery] = useAdminQuery()
  const rawMonth = initialQuery.get('month')
  useEffect(() => {
    if (rawMonth && !validMonth(rawMonth)) updateQuery({ month: currentMonth() })
  }, [rawMonth, updateQuery])
  const month = validMonth(initialQuery.get('month')) ?? currentMonth()
  const setMonth = useCallback((value: string) => updateQuery({ month: value }), [updateQuery])
  const [timezone, setTimezone] = useState('Asia/Seoul')
  const [hasChosenMonth, setHasChosenMonth] = useState(
    Boolean(validMonth(initialQuery.get('month'))),
  )
  const maximumMonth = currentMonth(timezone)
  const [q, setQ] = useState(initialQuery.get('q') ?? '')
  const appliedQ = initialQuery.get('q') ?? ''
  const setAppliedQ = (value: string) => updateQuery({ q: value || undefined, workspace_page: '1' })
  const page = adminPageNumber(initialQuery.get('workspace_page'))
  const setPage = (value: number | ((current: number) => number)) =>
    updateQuery({ workspace_page: String(typeof value === 'function' ? value(page) : value) })
  const [items, setItems] = useState<AdminWorkspaceSummary[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const selectedId = initialQuery.get('workspace')
  const setSelectedId = (value: string) => updateQuery({ workspace: value })
  // 계정별로 「지금 고른 작업 공간」. 목록이 바뀌어 저장해 둔 id가 사라지면 아래에서
  // 그 계정의 첫 작업 공간으로 되돌려 쓴다 — 따로 초기화하지 않아도 된다.
  const [selectedByOwner, setSelectedByOwner] = useState<Record<string, string>>({})
  const [reloadToken, setReloadToken] = useState(0)

  const searchId = useId()

  // 검색어·쪽·새로고침 신호가 바뀌었는지 렌더 중에 잰다("렌더 중 상태 조정" 패턴) —
  // effect 안에서 곧바로 setLoading(true)를 부르면 안 된다는 규칙을 지킨다.
  const queryKey = `${appliedQ}|${page}|${reloadToken}|${month}`
  const [renderedQueryKey, setRenderedQueryKey] = useState(queryKey)
  if (renderedQueryKey !== queryKey) {
    setRenderedQueryKey(queryKey)
    setLoading(true)
  }

  useEffect(() => {
    const controller = new AbortController()
    listAdminWorkspaces(
      {
        q: appliedQ === '' ? undefined : appliedQ,
        page,
        size: PAGE_SIZE,
        month: hasChosenMonth ? month : undefined,
      },
      controller.signal,
    )
      .then((response) => {
        if (controller.signal.aborted) return
        if (response.timezone) setTimezone(response.timezone)
        if (response.current_month && (!hasChosenMonth || month > response.current_month)) {
          setMonth(response.current_month)
          setHasChosenMonth(true)
        }
        setItems(response.items)
        setTotal(response.total)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setError(caught instanceof ApiError ? caught.message : LIST_ERROR_MESSAGE)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [appliedQ, page, reloadToken, month, hasChosenMonth, setMonth])

  function changeMonth(value: string) {
    if (validMonth(value, maximumMonth)) {
      setHasChosenMonth(true)
      updateQuery({ month: value, workspace_page: '1', credit_page: '1', payment_page: '1' })
    }
  }

  function handleSearchSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    setPage(1)
    setAppliedQ(q.trim())
  }

  const hasMore = page * PAGE_SIZE < total
  const groups = groupByOwner(items)

  return (
    <div className="flex flex-col gap-4">
      <p className="rounded-[10px] border border-border bg-muted px-4 py-3 text-sm text-muted-foreground">
        크레딧·구독·세금계산서는 계정이 아니라 작업 공간(워크스페이스) 단위입니다. 한 계정이 작업
        공간을 여러 개 가지면 잔액도 각각 따로입니다. 사용자의 이메일을 검색한 뒤 작업 공간을 고르고
        「관리」를 누르면 크레딧을 부여할 수 있습니다.
      </p>
      <div className="flex flex-wrap items-end gap-3" aria-label="월 선택">
        <Button
          disabled={month <= SERVICE_START_MONTH}
          onClick={() => changeMonth(shiftMonth(month, -1))}
        >
          이전 달
        </Button>
        <label className="field">
          조회 월
          <input
            type="month"
            value={month}
            min={SERVICE_START_MONTH}
            max={maximumMonth}
            onChange={(event) => changeMonth(event.target.value)}
          />
        </label>
        <Button disabled={month >= maximumMonth} onClick={() => changeMonth(shiftMonth(month, 1))}>
          다음 달
        </Button>
        <Button onClick={() => changeMonth(maximumMonth)}>이번 달</Button>
      </div>
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
        <div className="rounded-[16px] border border-border bg-card px-5 pb-5 shadow-sm">
          <table className="usage-table">
            <caption>
              이 쪽에 실린 워크스페이스를 계정별로 묶었습니다. 작업 공간이 여러 개인 계정은 골라서
              관리를 누르세요.
            </caption>
            <thead>
              <tr>
                <th scope="col">소유자 이메일</th>
                <th scope="col">작업 공간</th>
                <th scope="col">가용/사용 크레딧</th>
                <th scope="col">결제액(결제일)/환불액</th>
                <th scope="col">관리</th>
              </tr>
            </thead>
            <tbody>
              {groups.length === 0 ? (
                <tr>
                  <td colSpan={5} className="text-muted-foreground">
                    검색 결과가 없습니다.
                  </td>
                </tr>
              ) : (
                groups.map((group) => {
                  const selected =
                    group.workspaces.find(
                      (workspace) =>
                        workspace.workspace_id ===
                        (selectedByOwner[group.ownerEmail] ?? selectedId),
                    ) ?? group.workspaces[0]
                  return (
                    <tr key={group.ownerEmail}>
                      <th scope="row">{group.ownerEmail}</th>
                      <td>
                        {group.workspaces.length === 1 ? (
                          selected.name
                        ) : (
                          <select
                            aria-label={`${group.ownerEmail}의 작업 공간 선택`}
                            className="min-h-11 rounded-[10px] border border-input bg-card px-3 text-sm text-foreground"
                            value={selected.workspace_id}
                            onChange={(event) => {
                              const nextId = event.target.value
                              setSelectedByOwner((current) => ({
                                ...current,
                                [group.ownerEmail]: nextId,
                              }))
                              // 이 계정의 상세가 열려 있다면 상세·크레딧 폼도 새로 고른
                              // 작업 공간으로 옮긴다 — 열려 있지 않으면 건드리지 않는다.
                              if (
                                group.workspaces.some(
                                  (workspace) => workspace.workspace_id === selectedId,
                                )
                              ) {
                                setSelectedId(nextId)
                              }
                            }}
                          >
                            {group.workspaces.map((workspace) => (
                              <option key={workspace.workspace_id} value={workspace.workspace_id}>
                                {workspace.name}
                              </option>
                            ))}
                          </select>
                        )}
                      </td>
                      <td className="tabular-nums">
                        {formatCredits(selected.credit_available)} /{' '}
                        {selected.selected_month
                          ? formatCredits(selected.selected_month.credits)
                          : '확인 필요'}
                        <span className="block text-xs text-muted-foreground">
                          현재 가용 / {month} 사용
                        </span>
                      </td>
                      <td className="tabular-nums">
                        {selected.selected_month ? (
                          <AdminPaymentSummary
                            key={`${selected.workspace_id}|${month}|${reloadToken}|${selected.selected_month.paid_krw}|${selected.selected_month.refunded_krw}`}
                            workspaceId={selected.workspace_id}
                            month={month}
                            timezone={timezone}
                            events={selected.selected_month.recent_events}
                            paid={selected.selected_month.paid_krw}
                            refunded={selected.selected_month.refunded_krw}
                          />
                        ) : (
                          '확인 필요'
                        )}
                      </td>
                      <td>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          className="min-h-11"
                          aria-label={`${group.ownerEmail}의 ${selected.name} 관리`}
                          onClick={() => setSelectedId(selected.workspace_id)}
                        >
                          관리
                        </Button>
                      </td>
                    </tr>
                  )
                })
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
          key={selectedId}
          workspaceId={selectedId}
          month={month}
          onMonthChange={changeMonth}
          onCreditsAdjusted={() => setReloadToken((token) => token + 1)}
        />
      )}
    </div>
  )
}
