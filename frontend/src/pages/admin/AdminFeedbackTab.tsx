import { useEffect, useState } from 'react'
import { listAdminFeedback } from '../../api/admin'
import { ApiError } from '../../api/client'
import type { AdminFeedbackListResponse, PublishIntent } from '../../api/types'
import { Button } from '../../components/ui/Button'

const INTENT_LABELS: Record<PublishIntent, string> = {
  as_is: '그대로 쓸 수 있다',
  with_edits: '조금 고쳐서 쓰겠다',
  not_usable: '쓸 수 없다',
}

export function AdminFeedbackTab() {
  const [page, setPage] = useState(1)
  const [revision, setRevision] = useState(0)
  return (
    <FeedbackPage
      key={`${page}:${revision}`}
      page={page}
      onPage={setPage}
      onRefresh={() => setRevision((value) => value + 1)}
    />
  )
}

function FeedbackPage({
  page,
  onPage,
  onRefresh,
}: {
  page: number
  onPage: (page: number) => void
  onRefresh: () => void
}) {
  const [data, setData] = useState<AdminFeedbackListResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    listAdminFeedback({ page, size: 20 }, controller.signal)
      .then((response) => {
        if (!controller.signal.aborted) setData(response)
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted)
          setError(
            caught instanceof ApiError ? caught.message : '사용자 의견을 불러오지 못했습니다.',
          )
      })
    return () => controller.abort()
  }, [page])

  return (
    <section aria-label="사용자 의견 목록" className="space-y-4">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-muted-foreground">
          {data
            ? `총 ${data.total.toLocaleString('ko-KR')}건 · 최신순`
            : '최신순으로 의견을 확인합니다.'}
        </p>
        <Button variant="outline" onClick={onRefresh}>
          새로고침
        </Button>
      </div>
      {error && <p role="alert">{error}</p>}
      {!data && !error && <p role="status">의견을 불러오는 중입니다…</p>}
      {data && data.items.length === 0 && <p role="status">이 페이지에 제출된 의견이 없습니다.</p>}
      {data?.items.map((item) => (
        <article
          key={item.conversion_id}
          className="space-y-3 rounded-xl border border-border bg-card p-5"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="break-all font-semibold">{item.owner_email ?? '탈퇴한 사용자'}</h2>
            <time dateTime={item.submitted_at} className="text-sm text-muted-foreground">
              {new Date(item.submitted_at).toLocaleString('ko-KR')}
            </time>
          </div>
          <p className="text-sm">
            만족도 {item.quality_score}/5 · {INTENT_LABELS[item.publish_intent]} · 소요 시간{' '}
            {item.minutes_spent}분
          </p>
          <p className="whitespace-pre-wrap break-words">
            {item.comment_unreadable
              ? '저장된 의견을 읽을 수 없습니다.'
              : (item.comment ?? '자유 의견이 없거나 보존 기간이 끝났습니다.')}
          </p>
          <p className="break-all text-xs text-muted-foreground">변환 ID: {item.conversion_id}</p>
        </article>
      ))}
      {data && (
        <nav aria-label="의견 페이지" className="flex items-center justify-center gap-4">
          <Button variant="outline" disabled={page === 1} onClick={() => onPage(page - 1)}>
            이전
          </Button>
          <span>
            {page} / {Math.max(1, Math.ceil(data.total / data.size))}
          </span>
          <Button
            variant="outline"
            disabled={page * data.size >= data.total}
            onClick={() => onPage(page + 1)}
          >
            다음
          </Button>
        </nav>
      )}
    </section>
  )
}
