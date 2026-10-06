import { useEffect, useState } from 'react'
import {
  listAdminNotifications,
  resolveAdminNotification,
  retryAdminNotification,
} from '../../api/admin'
import { ApiError } from '../../api/client'
import { Button } from '../../components/ui/Button'
import { readOperation, saveOperation, useAdminOperationKey } from './adminOperationStorage'
import { adminPageNumber, useAdminQuery } from './useAdminQuery'

type Notification = Awaited<ReturnType<typeof listAdminNotifications>>['items'][number]
export function AdminNotificationsTab() {
  const [query, update] = useAdminQuery()
  const [refresh, setRefresh] = useState(0)
  const params = {
    page: query.get('notification') ? 1 : adminPageNumber(query.get('notifications_page')),
    state: query.get('notifications_state') || undefined,
    environment: query.get('notifications_environment') || undefined,
    event_type: query.get('notifications_event') || undefined,
    id:
      Number.isSafeInteger(Number(query.get('notification'))) &&
      Number(query.get('notification')) > 0
        ? Number(query.get('notification'))
        : undefined,
  }
  return (
    <section aria-label="메일 관리" className="space-y-4">
      <p>
        결과 미확정 메일은 미전달 확인을 기록한 뒤에만 재발송합니다. 수신처는 변경하지 않습니다.
      </p>
      <div className="flex flex-wrap gap-3">
        {[
          ['state', '상태'],
          ['environment', '환경'],
          ['event', '이벤트'],
        ].map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              value={query.get(`notifications_${key}`) ?? ''}
              onChange={(e) =>
                update({ [`notifications_${key}`]: e.target.value, notifications_page: '1' })
              }
            />
          </label>
        ))}
        <Button onClick={() => setRefresh((n) => n + 1)}>재조회</Button>
      </div>
      {query.get('notification') && (
        <Button
          variant="outline"
          onClick={() => update({ notification: undefined, notifications_page: '1' })}
        >
          전체 메일 목록
        </Button>
      )}
      <NotificationList
        key={`${JSON.stringify(params)}:${refresh}`}
        params={params}
        selected={query.get('notification')}
        onSelect={(id) => update({ notification: id, notifications_page: '1' })}
        onPage={(page) => update({ notifications_page: String(page) })}
        onRefresh={() => setRefresh((n) => n + 1)}
      />
    </section>
  )
}
function NotificationList({
  params,
  selected,
  onSelect,
  onPage,
  onRefresh,
}: {
  params: Parameters<typeof listAdminNotifications>[0]
  selected: string | null
  onSelect: (id: string) => void
  onPage: (page: number) => void
  onRefresh: () => void
}) {
  const [data, setData] = useState<Awaited<ReturnType<typeof listAdminNotifications>> | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    listAdminNotifications(params, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setData(result)
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '메일 조회 실패')
      })
    return () => controller.abort()
  }, [params])
  return (
    <>
      {error && <p role="alert">{error}</p>}
      {!data && !error && <p role="status">메일을 불러오는 중입니다…</p>}
      {data && (
        <>
          <p>총 {data.total}건</p>
          {data.items.map((item) => (
            <article key={item.id} className="rounded-xl border p-4">
              <button
                className="text-primary underline"
                onClick={() => onSelect(String(item.id))}
                aria-expanded={selected === String(item.id)}
              >
                {item.event_type} · {item.state} · {item.environment ?? '환경 미상'}
              </button>
              <p>
                작업공간: {item.workspace_id ?? '없음'} · 생성:{' '}
                {new Date(item.created_at).toLocaleString('ko-KR')}
              </p>
              {selected === String(item.id) && (
                <NotificationDetail item={item} onRefresh={onRefresh} />
              )}
            </article>
          ))}
          <nav aria-label="메일 페이지" className="flex gap-3">
            <Button disabled={data.page <= 1} onClick={() => onPage(data.page - 1)}>
              이전
            </Button>
            <span>{data.page}쪽</span>
            <Button
              disabled={data.page * data.size >= data.total}
              onClick={() => onPage(data.page + 1)}
            >
              다음
            </Button>
          </nav>
        </>
      )}
    </>
  )
}
function NotificationDetail({
  item: initialItem,
  onRefresh,
}: {
  item: Notification
  onRefresh: () => void
}) {
  const [item, setItem] = useState(initialItem)
  const storageKey = useAdminOperationKey(item.workspace_id, `notification:${item.id}`)
  type Pending = {
    action: string
    request: {
      operation_id: string
      expected_revision: number
      reason: string
      resolution?: 'delivered' | 'not_delivered'
    }
  }
  const stored = readOperation<Pending>(storageKey)
  const restored =
    stored &&
    ['retry', 'delivered', 'not_delivered'].includes(stored.action) &&
    typeof stored.request?.operation_id === 'string' &&
    typeof stored.request?.reason === 'string' &&
    Number.isInteger(stored.request?.expected_revision)
      ? stored
      : null
  const [reason, setReason] = useState(restored?.request.reason ?? '')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [targetMissing, setTargetMissing] = useState(false)
  const [pending, setPending] = useState<Pending | null>(restored)
  async function act(action: 'retry' | 'delivered' | 'not_delivered') {
    const operation = pending ?? {
      action,
      request: {
        operation_id: crypto.randomUUID(),
        expected_revision: item.revision,
        reason: reason.trim(),
        ...(action === 'retry' ? {} : { resolution: action }),
      },
    }
    try {
      saveOperation(storageKey, operation)
    } catch {
      setError('복구 정보를 저장할 수 없어 요청하지 않았습니다.')
      return
    }
    setPending(operation)
    setBusy(true)
    setError('')
    try {
      if (operation.action === 'retry') await retryAdminNotification(item.id, operation.request)
      else
        await resolveAdminNotification(item.id, {
          ...operation.request,
          resolution: operation.request.resolution!,
        })
      saveOperation(storageKey, null)
      setPending(null)
      onRefresh()
    } catch (e) {
      if (e instanceof ApiError && (e.status === 422 || e.status === 404)) {
        // These responses definitively reject this submitted command. Preserve
        // uncertain/network and unproven 409 requests through the separate path below.
        saveOperation(storageKey, null)
        setPending(null)
        let missing = e.status === 404
        try {
          const latest = (
            await listAdminNotifications({ id: item.id, page: 1, size: 1 })
          ).items.find((value) => value.id === item.id)
          missing = !latest
          if (latest) setItem(latest)
        } catch {
          // The command rejection is known even if the follow-up read is unavailable.
        }
        setTargetMissing(missing)
        setError(
          missing
            ? '메일 대상을 찾을 수 없습니다. 목록을 재조회해 주세요.'
            : `${e.message} 요청은 적용되지 않았습니다. 사유를 수정하거나 최신 상태를 재조회해 주세요.`,
        )
        return
      }
      if (e instanceof ApiError && e.status === 409) {
        try {
          // Reconcile by ID independently of the current queue filters. A rejected
          // transaction must be distinguished from an applied request whose reply was lost.
          const latest = (
            await listAdminNotifications({ id: item.id, page: 1, size: 1 })
          ).items.find((value) => value.id === item.id)
          if (
            latest?.resolutions.some(
              (record) => record.operation_id === operation.request.operation_id,
            )
          ) {
            saveOperation(storageKey, null)
            setPending(null)
            onRefresh()
            return
          }
          if (latest && latest.revision !== operation.request.expected_revision) {
            saveOperation(storageKey, null)
            setPending(null)
            setItem(latest)
            setError(
              '다른 조치로 상태가 변경되어 이 요청은 적용되지 않았습니다. 최신 상태를 확인하고 사유를 수정해 다시 요청하세요.',
            )
            return
          }
        } catch {
          // No authoritative fresh result: retain the original UUID and content.
        }
      }
      setError(
        `${e instanceof Error ? e.message : '요청 실패'} 서버 사유와 환경을 확인하고 재조회한 뒤 같은 요청으로 재시도하세요.`,
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-3">
      <p>
        대상 작업공간: {item.workspace_name ?? item.workspace_id} · 현재 수신 이메일:{' '}
        {item.recipient_email ?? '수신처 없음'}
      </p>
      <p>현재 수신처를 확인한 뒤 재발송하세요. 과거 시도의 실제 수신처를 추정하지 않습니다.</p>
      <p>
        최근 시도: {item.attempted_at ?? '기록 없음'} · 발송: {item.sent_at ?? '확인되지 않음'} ·
        운영자 확인: {item.resolution ?? '없음'}
      </p>
      <p>최근 실패 코드: {item.failure_code ?? '기록 없음'}</p>
      <ul aria-label="발송 시도 이력">
        {item.attempts.map((attempt) => (
          <li key={attempt.id}>
            {attempt.state} · {attempt.started_at} ~ {attempt.finished_at ?? '미완료'} · 실패 코드:{' '}
            {attempt.failure_code ?? '기록 없음'}
          </li>
        ))}
      </ul>
      <ul aria-label="운영자 확인 기록">
        {item.resolutions.map((record) => (
          <li key={record.operation_id}>
            {record.actor_user_id} · {record.action} · {record.reason} · {record.created_at}
          </li>
        ))}
      </ul>
      <label>
        확인 사유
        <input
          value={reason}
          disabled={!!pending}
          onChange={(e) => setReason(e.target.value)}
          maxLength={200}
        />
      </label>
      {error && <p role="alert">{error}</p>}
      <div className="flex flex-wrap gap-2">
        {(['delivered', 'not_delivered', 'retry'] as const).map((action) => (
          <Button
            key={action}
            disabled={
              busy ||
              targetMissing ||
              !reason.trim() ||
              (!!pending && pending.action !== action) ||
              (!pending && (action === 'retry' ? !item.retry_allowed : !item.resolve_allowed))
            }
            onClick={() => void act(action)}
          >
            {action === 'delivered'
              ? '전달 확인 기록'
              : action === 'not_delivered'
                ? '미전달 확인 기록'
                : '재발송 예약'}
          </Button>
        ))}
      </div>
      {!item.retry_allowed && (
        <p>{item.retry_blocked_reason ?? '현재 상태 또는 환경에서는 재발송할 수 없습니다.'}</p>
      )}
    </div>
  )
}
