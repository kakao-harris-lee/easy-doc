import { useEffect, useState } from 'react'
import { listAdminOperations } from '../../api/admin'
import { Button } from '../../components/ui/Button'
import { adminPageNumber, useAdminQuery } from './useAdminQuery'

export function AdminOperationsTab() {
  const [query, update] = useAdminQuery()
  const page = adminPageNumber(query.get('operations_page'))
  const kind = query.get('operations_kind') || undefined
  const state = query.get('operations_state') || undefined
  const environment = query.get('operations_environment') || undefined
  const [refresh, setRefresh] = useState(0)
  const key = `${page}|${kind}|${state}|${environment}|${refresh}`
  return (
    <OperationsPage
      key={key}
      params={{ page, kind, state, environment }}
      update={update}
      onRefresh={() => setRefresh((n) => n + 1)}
    />
  )
}
function OperationsPage({
  params,
  update,
  onRefresh,
}: {
  params: Parameters<typeof listAdminOperations>[0]
  update: (values: Record<string, string | undefined>) => void
  onRefresh: () => void
}) {
  const [data, setData] = useState<Awaited<ReturnType<typeof listAdminOperations>> | null>(null)
  const [error, setError] = useState('')
  const [now] = useState(() => Date.now())
  useEffect(() => {
    const controller = new AbortController()
    listAdminOperations(params, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setData(result)
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setError(error instanceof Error ? error.message : '처리할 일을 불러오지 못했습니다.')
      })
    return () => controller.abort()
  }, [params])
  return (
    <section aria-label="처리할 일" className="space-y-4">
      <p>고객 수가 아닌 작업 건수입니다. 확인 필요와 진행 중 상태를 구분해 확인하세요.</p>
      <div className="flex flex-wrap gap-3">
        <label>
          작업 종류
          <select
            value={params?.kind ?? ''}
            onChange={(e) => update({ operations_kind: e.target.value, operations_page: '1' })}
          >
            <option value="">전체</option>
            {['payment', 'refund', 'card_deletion', 'notification', 'invoice'].map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
        </label>
        <label>
          작업 상태
          <input
            defaultValue={params?.state ?? ''}
            onBlur={(e) => update({ operations_state: e.target.value, operations_page: '1' })}
          />
        </label>
        <label>
          결제 환경
          <select
            value={params?.environment ?? ''}
            onChange={(e) =>
              update({ operations_environment: e.target.value, operations_page: '1' })
            }
          >
            <option value="">전체</option>
            <option value="toss_test">테스트</option>
            <option value="toss_live">운영</option>
            <option value="unknown">미상</option>
          </select>
        </label>
        <Button variant="outline" onClick={onRefresh}>
          재조회
        </Button>
      </div>
      {error && <p role="alert">{error}</p>}
      {!data && !error && <p role="status">처리할 일을 불러오는 중입니다…</p>}
      {data && (
        <>
          <p>총 {data.total} 작업 건</p>
          <ul aria-label="작업 종류별 집계">
            {Object.entries(data.counts).map(([kind, count]) => (
              <li key={kind}>
                {kind}: {count}건
              </li>
            ))}
          </ul>
          {data.items.length === 0 && <p>처리할 일이 없습니다.</p>}
          <ul className="space-y-3">
            {data.items.map((item) => (
              <li key={`${item.kind}:${item.id}`} className="rounded-xl border p-4">
                <p>
                  {item.severity === 2 ? '확인 필요' : '진행 중'} · {item.kind} · {item.state} ·{' '}
                  {item.environment ?? '환경 미상'}
                </p>
                <p>{item.workspace_name ?? item.workspace_id ?? '고객 정보 없음'}</p>
                <p>
                  {item.created_at ? (
                    <>
                      <time dateTime={item.created_at}>
                        {new Date(item.created_at).toLocaleString('ko-KR')}
                      </time>{' '}
                      · 경과 {Math.max(0, Math.floor((now - Date.parse(item.created_at)) / 60000))}
                      분
                    </>
                  ) : (
                    '시각 기록 없음 · 경과 시간 확인 불가'
                  )}
                </p>
                <p>다음 조치: {item.next_action}</p>
                <a
                  className="text-primary underline"
                  href={
                    item.kind === 'notification'
                      ? `/admin?tab=notifications&notification=${encodeURIComponent(item.id)}`
                      : item.kind === 'invoice'
                        ? `/admin?tab=invoices&invoice=${encodeURIComponent(item.id)}&invoice_status=all`
                        : `/admin?tab=workspaces&workspace=${encodeURIComponent(item.workspace_id ?? '')}`
                  }
                >
                  상세 확인
                </a>
              </li>
            ))}
          </ul>
          <nav aria-label="처리할 일 페이지" className="flex gap-3">
            <Button
              disabled={data.page <= 1}
              onClick={() => update({ operations_page: String(data.page - 1) })}
            >
              이전
            </Button>
            <span>{data.page}쪽</span>
            <Button
              disabled={data.page * data.size >= data.total}
              onClick={() => update({ operations_page: String(data.page + 1) })}
            >
              다음
            </Button>
          </nav>
        </>
      )}
    </section>
  )
}
