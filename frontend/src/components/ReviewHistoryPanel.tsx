import { useEffect, useId, useRef, useState } from 'react'
import { Download, RefreshCw } from 'lucide-react'

import { ApiError, downloadReviewHistory, getReviewHistory } from '../api/client'
import type { ReviewHistoryEvent } from '../api/types'
import { Button } from './ui/Button'

interface ReviewHistoryPanelProps {
  conversionId: string
  contentRevision: number
  refreshToken?: number
}

const EVENT_LABELS: Record<ReviewHistoryEvent['event_type'], string> = {
  item_confirmed: '검수 항목 확인',
  item_reopened: '검수 항목 확인 취소',
  item_not_applicable: '검수 항목 해당 없음',
  guide_reviewed: '행동 안내 확인',
  invalidated_by_edit: '내용 변경으로 확인 무효화',
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

function errorText(caught: unknown, fallback: string): string {
  return caught instanceof ApiError ? caught.message : fallback
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

function HistoryEventCard({
  event,
  currentRevision,
}: {
  event: ReviewHistoryEvent
  currentRevision: number
}) {
  const stale = event.content_revision !== currentRevision
  const snapshot = event.snapshot

  return (
    <li className="rounded-[10px] border border-border p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="font-semibold">{EVENT_LABELS[event.event_type]}</h3>
        {stale && <span className="text-sm font-semibold text-warning">현재 본문과 다름</span>}
      </div>
      <dl className="mt-3 grid gap-1 text-sm text-muted-foreground sm:grid-cols-2">
        <div>
          <dt className="inline font-semibold text-foreground">시각: </dt>
          <dd className="inline">{formatDate(event.created_at)}</dd>
        </div>
        <div>
          <dt className="inline font-semibold text-foreground">담당자: </dt>
          <dd className="inline">{event.actor_user_id}</dd>
        </div>
        <div>
          <dt className="inline font-semibold text-foreground">본문 버전: </dt>
          <dd className="inline">{event.content_revision}</dd>
        </div>
        {event.artifact_revision !== null && (
          <div>
            <dt className="inline font-semibold text-foreground">산출물 버전: </dt>
            <dd className="inline">{event.artifact_revision}</dd>
          </div>
        )}
      </dl>
      {snapshot.status === 'missing' ? (
        <p className="mt-3 text-sm text-warning">본문 스냅샷 없음</p>
      ) : snapshot.content_text === null ? (
        <p className="mt-3 text-sm text-muted-foreground">당시 본문 내용이 기록되지 않았습니다.</p>
      ) : (
        <details className="mt-3">
          <summary className="cursor-pointer text-sm font-semibold">당시 본문 보기</summary>
          <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-md bg-muted p-3 text-sm">
            {snapshot.content_text}
          </pre>
        </details>
      )}
    </li>
  )
}

/** R5가 켜진 변환에서 서버가 기록한 검수 이벤트를 최근순으로 보여준다. */
export function ReviewHistoryPanel({
  conversionId,
  contentRevision,
  refreshToken = 0,
}: ReviewHistoryPanelProps) {
  const headingId = useId()
  const [events, setEvents] = useState<ReviewHistoryEvent[]>([])
  const [currentRevision, setCurrentRevision] = useState(contentRevision)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [manualRefresh, setManualRefresh] = useState(0)
  const requestVersion = useRef(0)
  const moreController = useRef<AbortController | null>(null)
  const exportController = useRef<AbortController | null>(null)

  useEffect(() => {
    const version = requestVersion.current + 1
    requestVersion.current = version
    const controller = new AbortController()
    moreController.current?.abort()
    exportController.current?.abort()
    async function loadFirstPage(): Promise<void> {
      // Defer the reset into the request task. This keeps the effect focused on starting and
      // cancelling the external request while still clearing stale pages before its response.
      await Promise.resolve()
      if (controller.signal.aborted || requestVersion.current !== version) return
      setEvents([])
      setNextCursor(null)
      setCurrentRevision(contentRevision)
      setLoading(true)
      setLoadingMore(false)
      setExporting(false)
      setError(null)
      setNotice(null)
      try {
        const response = await getReviewHistory(conversionId, { limit: 20 }, controller.signal)
        if (controller.signal.aborted || requestVersion.current !== version) return
        setEvents(response.events)
        setNextCursor(response.next_cursor)
        setCurrentRevision(response.current_content_revision)
      } catch (caught) {
        if (controller.signal.aborted || requestVersion.current !== version) return
        setError(errorText(caught, '검수 기록을 불러오지 못했습니다. 다시 시도해 주세요.'))
      } finally {
        if (!controller.signal.aborted && requestVersion.current === version) setLoading(false)
      }
    }
    void loadFirstPage()

    return () => {
      controller.abort()
      moreController.current?.abort()
      exportController.current?.abort()
    }
  }, [conversionId, contentRevision, refreshToken, manualRefresh])

  useEffect(
    () => () => {
      moreController.current?.abort()
      exportController.current?.abort()
    },
    [],
  )

  async function loadMore(): Promise<void> {
    const cursor = nextCursor
    if (cursor === null || loadingMore) return
    const version = requestVersion.current
    const controller = new AbortController()
    moreController.current?.abort()
    moreController.current = controller
    setLoadingMore(true)
    setError(null)
    try {
      const response = await getReviewHistory(
        conversionId,
        { cursor, limit: 20 },
        controller.signal,
      )
      if (controller.signal.aborted || requestVersion.current !== version) return
      setEvents((current) => {
        const known = new Set(current.map((event) => event.event_id))
        return [...current, ...response.events.filter((event) => !known.has(event.event_id))]
      })
      setNextCursor(response.next_cursor === cursor ? null : response.next_cursor)
      setCurrentRevision(response.current_content_revision)
    } catch (caught) {
      if (!controller.signal.aborted && requestVersion.current === version) {
        setError(errorText(caught, '검수 기록을 더 불러오지 못했습니다. 다시 시도해 주세요.'))
      }
    } finally {
      if (!controller.signal.aborted && requestVersion.current === version) setLoadingMore(false)
    }
  }

  async function exportHistory(): Promise<void> {
    const version = requestVersion.current
    const controller = new AbortController()
    exportController.current?.abort()
    exportController.current = controller
    setExporting(true)
    setError(null)
    try {
      const downloaded = await downloadReviewHistory(conversionId, controller.signal)
      if (controller.signal.aborted || requestVersion.current !== version) return
      saveBlob(downloaded.blob, downloaded.filename ?? 'review-history.txt')
      setNotice('검수 기록 TXT를 내려받았습니다.')
    } catch (caught) {
      if (!controller.signal.aborted && requestVersion.current === version) {
        setError(errorText(caught, '검수 기록 TXT를 내려받지 못했습니다. 다시 시도해 주세요.'))
      }
    } finally {
      if (!controller.signal.aborted && requestVersion.current === version) {
        setExporting(false)
      }
    }
  }

  return (
    <section
      className="rounded-xl border border-border bg-card p-4 sm:p-6"
      aria-labelledby={headingId}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id={headingId} className="text-lg font-semibold">
            검수 기록
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            서버가 기록한 최근 검수 행위입니다. 법적 인증서나 영구 감사 기록은 아닙니다.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={() => setManualRefresh((value) => value + 1)}
          >
            <RefreshCw className="size-4" aria-hidden="true" />
            새로 고침
          </Button>
          <Button
            type="button"
            variant="outline"
            loading={exporting}
            onClick={() => void exportHistory()}
          >
            {!exporting && <Download className="size-4" aria-hidden="true" />}
            TXT로 내려받기
          </Button>
        </div>
      </div>

      {loading && (
        <p className="mt-5" role="status">
          검수 기록을 불러오는 중입니다…
        </p>
      )}
      {error !== null && (
        <div className="form-error mt-4" role="alert">
          {error}
        </div>
      )}
      {notice !== null && (
        <p className="mt-4 text-sm text-muted-foreground" role="status">
          {notice}
        </p>
      )}
      {!loading && error === null && events.length === 0 && (
        <p className="mt-5 text-sm text-muted-foreground">아직 검수 기록이 없습니다.</p>
      )}
      {events.length > 0 && (
        <ol className="mt-5 flex flex-col gap-3">
          {events.map((event) => (
            <HistoryEventCard
              key={event.event_id}
              event={event}
              currentRevision={currentRevision}
            />
          ))}
        </ol>
      )}
      {nextCursor !== null && (
        <Button
          type="button"
          variant="outline"
          className="mt-4 w-full"
          loading={loadingMore}
          disabled={loadingMore}
          onClick={() => void loadMore()}
        >
          더 보기
        </Button>
      )}
    </section>
  )
}
