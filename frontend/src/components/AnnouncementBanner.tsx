import { useEffect, useState } from 'react'
import { X } from 'lucide-react'

import { listActiveAnnouncements } from '../api/announcements'
import type { ActiveAnnouncementResponse } from '../api/types'
import { useAuth } from '../auth/context'
import { Button } from './ui/Button'

/**
 * 닫은 공지 id를 모아 두는 `localStorage` 키. 값은 JSON 문자열 배열이다(어드민 최소
 * 계획 §2 결정 5 — 닫기는 브라우저 로컬 저장, 계약에 없다).
 */
const DISMISSED_STORAGE_KEY = 'easydoc.dismissed_announcement_ids'

/** 저장된 닫은 id 목록을 읽는다. 접근이 막혔거나(프라이빗 모드) 값이 깨졌으면 빈 집합. */
function readDismissedIds(): Set<string> {
  try {
    const raw = window.localStorage.getItem(DISMISSED_STORAGE_KEY)
    if (raw === null) {
      return new Set()
    }
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed)
      ? new Set(parsed.filter((id): id is string => typeof id === 'string'))
      : new Set()
  } catch {
    return new Set()
  }
}

/** 닫은 id 목록을 저장한다. 실패해도 화면 갱신은 그대로 진행한다(보조 저장일 뿐이다). */
function writeDismissedIds(ids: Set<string>): void {
  try {
    window.localStorage.setItem(DISMISSED_STORAGE_KEY, JSON.stringify(Array.from(ids)))
  } catch {
    // 다음 새로고침에서 다시 보이는 정도의 대가다 — 화면 흐름을 막지 않는다.
  }
}

/**
 * 활성 공지 배너 (어드민 최소, 계약 2.25.0).
 *
 * 인증 사용자의 세션마다 한 번만 `GET /announcements/active`를 부른다 — `AppLayout`이
 * 화면 전환마다 다시 그려지는 자리이므로, 여기서 다시 걸면 화면을 옮길 때마다 조회가
 * 나간다. 닫은 공지는 id별로 `localStorage`에 남겨 새로고침해도 다시 뜨지 않는다
 * (계획 §2 결정 5).
 *
 * 조회 실패는 조용히 넘어간다 — 이 배너는 보조 정보이지 화면의 핵심 흐름이 아니다.
 */
export function AnnouncementBanner() {
  const { status } = useAuth()
  const [announcements, setAnnouncements] = useState<ActiveAnnouncementResponse[]>([])
  const [dismissedIds, setDismissedIds] = useState<Set<string>>(() => readDismissedIds())

  useEffect(() => {
    if (status !== 'authenticated') {
      return
    }
    const controller = new AbortController()
    listActiveAnnouncements(controller.signal)
      .then((response) => setAnnouncements(response.items))
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        // 조회 실패는 배너를 그냥 비운 채로 둔다 — 오류 문구로 다른 화면을 가리지 않는다.
      })
    return () => controller.abort()
  }, [status])

  function dismiss(id: string): void {
    // 상태 갱신 함수 안에서 저장(부수효과)까지 하지 않는다 — 업데이터는 두 번 불릴 수
    // 있다(React StrictMode의 재실행 등). 다음 값을 현재 상태에서 미리 계산해 저장한
    // 뒤 그 값으로 상태를 바꾼다.
    const next = new Set(dismissedIds)
    next.add(id)
    writeDismissedIds(next)
    setDismissedIds(next)
  }

  // 서버가 이미 최신순 최대 5건만 주지만(계약), 화면에서도 상한을 다시 지킨다 —
  // 서버 응답이 계약을 벗어나도 배너 하나가 화면을 뒤덮지 않는다.
  const visible = announcements
    .filter((announcement) => !dismissedIds.has(announcement.id))
    .slice(0, 5)
  if (visible.length === 0) {
    return null
  }

  return (
    <div role="region" aria-label="공지" className="border-b border-border bg-accent/40">
      <div className="mx-auto flex w-full max-w-[1200px] flex-col gap-2 px-4 py-2 md:px-6 xl:px-8">
        {visible.map((announcement) => (
          <div
            key={announcement.id}
            className="flex items-start justify-between gap-3 rounded-[10px] border border-border bg-card px-4 py-3"
          >
            <p className="m-0 text-sm text-foreground">{announcement.body}</p>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="shrink-0"
              // 배너가 여러 개면 "닫기"만으로는 어떤 공지를 닫는지 낭독기가 구분하지
              // 못한다 — 본문 앞부분을 이름에 실어 준다(`HistoryPage`의 삭제 버튼과
              // 같은 이유).
              aria-label={`닫기 — ${announcement.body.slice(0, 20)}`}
              onClick={() => dismiss(announcement.id)}
            >
              <X className="size-4" aria-hidden="true" />
              닫기
            </Button>
          </div>
        ))}
      </div>
    </div>
  )
}
