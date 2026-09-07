import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../../api/client'
import {
  createAdminAnnouncement,
  listAdminAnnouncements,
  updateAdminAnnouncement,
} from '../../api/admin'
import type { AnnouncementResponse } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'

/** `AnnouncementCreateRequest.body`·`AnnouncementUpdateRequest.body`와 같은 상한. */
const MAX_BODY_LENGTH = 500

const LIST_ERROR_MESSAGE = '공지 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
const CREATE_ERROR_MESSAGE = '공지를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
const UPDATE_ERROR_MESSAGE = '공지를 수정하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 공지 만들기 폼. 성공하면 새 공지를 위로 얹고 문구는 비운다. */
function CreateAnnouncementForm({
  onCreated,
}: {
  onCreated: (created: AnnouncementResponse) => void
}) {
  const [body, setBody] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const bodyId = useId()

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setError(null)
    if (body.trim() === '') {
      setError('공지 내용을 입력해 주세요.')
      return
    }
    setSubmitting(true)
    try {
      const created = await createAdminAnnouncement({ body })
      setBody('')
      onCreated(created)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : CREATE_ERROR_MESSAGE)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      className="flex flex-col gap-3 rounded-[12px] border border-border bg-card p-4"
      aria-label="공지 만들기"
      onSubmit={(event) => void handleSubmit(event)}
    >
      {error !== null && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="field">
        <label htmlFor={bodyId}>공지 내용</label>
        <textarea
          id={bodyId}
          className="review-textarea min-h-24"
          maxLength={MAX_BODY_LENGTH}
          value={body}
          onChange={(event) => setBody(event.target.value)}
        />
        <p className="field-hint">{MAX_BODY_LENGTH}자 이내. 만들면 바로 활성으로 게시됩니다.</p>
      </div>
      <Button type="submit" loading={submitting} className="self-start">
        {submitting ? '만드는 중…' : '공지 만들기'}
      </Button>
    </form>
  )
}

/** 공지 한 줄 — 본문 미리보기, 활성 토글, 본문 고치기(펼치면 폼이 된다). */
function AnnouncementRow({
  announcement,
  onUpdated,
}: {
  announcement: AnnouncementResponse
  onUpdated: (updated: AnnouncementResponse) => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(announcement.body)
  const [toggling, setToggling] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const bodyId = useId()
  // 고치기 폼이 이 id로 aria-describedby를 건다 — 원문 미리보기가 늘 DOM에 있어야
  // 참조가 실재한다("aria 참조가 실재하는 요소를 가리킨다" 규칙, a11y.test.tsx).
  const previewId = useId()

  async function handleToggleActive(): Promise<void> {
    setError(null)
    setToggling(true)
    try {
      const updated = await updateAdminAnnouncement(announcement.id, {
        active: !announcement.active,
      })
      onUpdated(updated)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : UPDATE_ERROR_MESSAGE)
    } finally {
      setToggling(false)
    }
  }

  async function handleSave(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setError(null)
    if (draft.trim() === '') {
      setError('공지 내용을 입력해 주세요.')
      return
    }
    setSaving(true)
    try {
      const updated = await updateAdminAnnouncement(announcement.id, { body: draft })
      onUpdated(updated)
      setEditing(false)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : UPDATE_ERROR_MESSAGE)
    } finally {
      setSaving(false)
    }
  }

  return (
    <li className="flex flex-col gap-3 rounded-[12px] border border-border bg-card p-4">
      {error !== null && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <Badge tone={announcement.active ? 'success' : 'neutral'}>
            {announcement.active ? '활성' : '비활성'}
          </Badge>
          {/* 고치는 중에도 감추지 않는다 — 고치기 폼의 aria-describedby가 이 문단을
              가리키므로(원문 확인용), 편집 중에 사라지면 깨진 참조가 된다. */}
          <p id={previewId} className="mt-2 whitespace-pre-wrap text-foreground">
            {announcement.body}
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            loading={toggling}
            onClick={() => void handleToggleActive()}
          >
            {announcement.active ? '비활성으로 전환' : '활성으로 전환'}
          </Button>
          {!editing && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                setDraft(announcement.body)
                setEditing(true)
              }}
            >
              고치기
            </Button>
          )}
        </div>
      </div>

      {editing && (
        <form
          className="flex flex-col gap-3"
          aria-label="공지 고치기"
          aria-describedby={previewId}
          onSubmit={(event) => void handleSave(event)}
        >
          <div className="field">
            <label htmlFor={bodyId}>공지 내용</label>
            <textarea
              id={bodyId}
              className="review-textarea min-h-24"
              maxLength={MAX_BODY_LENGTH}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
            />
          </div>
          <div className="flex gap-3">
            <Button type="submit" loading={saving}>
              {saving ? '저장하는 중…' : '저장'}
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setEditing(false)}
              disabled={saving}
            >
              취소
            </Button>
          </div>
        </form>
      )}
    </li>
  )
}

/** 「공지」 탭 — 작성·활성 토글·본문 고치기 (어드민 최소, 계약 2.25.0). */
export function AdminAnnouncementsTab() {
  const [items, setItems] = useState<AnnouncementResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    listAdminAnnouncements(controller.signal)
      .then((response) => {
        setItems(response.items)
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
  }, [])

  function handleCreated(created: AnnouncementResponse): void {
    setItems((current) => [created, ...current])
  }

  function handleUpdated(updated: AnnouncementResponse): void {
    setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)))
  }

  return (
    <div className="flex flex-col gap-4">
      <CreateAnnouncementForm onCreated={handleCreated} />

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
        <ul aria-label="공지 목록" className="flex flex-col gap-3">
          {items.length === 0 ? (
            <li className="text-muted-foreground">아직 만든 공지가 없습니다.</li>
          ) : (
            items.map((item) => (
              <AnnouncementRow key={item.id} announcement={item} onUpdated={handleUpdated} />
            ))
          )}
        </ul>
      )}
    </div>
  )
}
