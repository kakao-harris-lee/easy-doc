import { useEffect, useId, useRef, useState } from 'react'

import { ApiError, getExplanations } from '../api/client'
import type { Explanation } from '../api/types'
import { Button } from './ui/Button'

interface ExplanationsPanelProps {
  conversionId: string
  contentRevision: number
  dirty: boolean
  sourceAvailable: boolean
  onNavigateSource: (indexes: number[], trigger: HTMLButtonElement) => void
}

function errorText(caught: unknown, fallback: string): string {
  return caught instanceof ApiError ? caught.message : fallback
}

/** 목록 항목 하나. 펼침 상태는 로컬에만 있고 저장·서버 반영을 하지 않는다. */
function ExplanationCard({
  item,
  sourceAvailable,
  onNavigateSource,
}: {
  item: Explanation
  sourceAvailable: boolean
  onNavigateSource: (indexes: number[], trigger: HTMLButtonElement) => void
}) {
  const panelId = useId()
  const [open, setOpen] = useState(false)
  const hasAnchors = item.source_anchors.length > 0 && sourceAvailable

  return (
    <li className="rounded-[10px] border border-border p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <span className="font-semibold">{item.term}</span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="min-h-11"
          aria-expanded={open}
          // 패널은 접혔을 때 DOM에 없다. `aria-controls`도 그때만 건다 — 없는 id를
          // 가리키는 참조는 낭독기가 따라갈 대상이 없어 깨진 관계로 남는다.
          aria-controls={open ? panelId : undefined}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? '설명 접기' : '설명 더 보기'}
        </Button>
      </div>
      {open && (
        <div id={panelId} className="mt-3 flex flex-col gap-2">
          <p className="text-sm text-foreground">{item.explanation}</p>
          {hasAnchors ? (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={(event) =>
                onNavigateSource(
                  item.source_anchors.flatMap((anchor) => anchor.source_unit_indexes),
                  event.currentTarget,
                )
              }
            >
              원문 보기
            </Button>
          ) : (
            <p className="text-sm text-muted-foreground">원문 위치를 찾지 못했습니다.</p>
          )}
        </div>
      )}
    </li>
  )
}

/**
 * R6 — 사전 검수 정의에서 파생한 용어 설명 목록. 본문에 실제로 있어야 하는 조건·금액·
 * 기한을 대신하지 않는 보충 설명이라는 점을 안내문으로 먼저 말한다(AC-R6).
 */
export function ExplanationsPanel({
  conversionId,
  contentRevision,
  dirty,
  sourceAvailable,
  onNavigateSource,
}: ExplanationsPanelProps) {
  const headingId = useId()
  const [explanations, setExplanations] = useState<Explanation[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const requestVersion = useRef(0)

  useEffect(() => {
    const version = requestVersion.current + 1
    requestVersion.current = version
    const controller = new AbortController()
    async function load(): Promise<void> {
      // 다음 요청의 상태 초기화보다 먼저 지워지는 깜빡임을 막기 위해, 초기화를
      // 요청 태스크 안으로 미룬다(ReviewHistoryPanel과 같은 이유).
      await Promise.resolve()
      if (controller.signal.aborted || requestVersion.current !== version) return
      setLoading(true)
      setError(null)
      try {
        const response = await getExplanations(conversionId, controller.signal)
        if (controller.signal.aborted || requestVersion.current !== version) return
        setExplanations(response.explanations)
      } catch (caught) {
        if (controller.signal.aborted || requestVersion.current !== version) return
        setError(errorText(caught, '용어 설명을 불러오지 못했습니다. 다시 시도해 주세요.'))
      } finally {
        if (!controller.signal.aborted && requestVersion.current === version) setLoading(false)
      }
    }
    void load()

    return () => controller.abort()
  }, [conversionId, contentRevision])

  return (
    <section
      className="mt-6 rounded-xl border border-border bg-card p-4 sm:p-6"
      aria-labelledby={headingId}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold">
          용어 설명
        </h2>
        {dirty && <span className="text-sm font-semibold text-warning">현재 본문과 다름</span>}
      </div>
      <p className="mt-1 text-sm text-muted-foreground">
        사전에서 검수를 마친 용어를 바탕으로 한 보충 설명입니다. 본문에 있어야 하는 조건·금액·
        기한을 대신하지 않습니다.
      </p>

      {dirty && (
        <p className="mt-3 rounded-[10px] border border-warning/25 bg-warning-surface p-3 text-sm text-warning">
          수정 중인 글입니다. 본문을 저장한 뒤 다시 확인해 주세요.
        </p>
      )}

      {loading && (
        <p className="mt-5" role="status">
          용어 설명을 불러오는 중입니다…
        </p>
      )}
      {error !== null && (
        <div className="form-error mt-4" role="alert">
          {error}
        </div>
      )}
      {!loading && error === null && explanations.length === 0 && (
        <p className="mt-5 text-sm text-muted-foreground">
          이 본문에서 설명을 제공할 용어가 없습니다.
        </p>
      )}
      {explanations.length > 0 && (
        <ul className="mt-5 flex flex-col gap-3">
          {explanations.map((item, index) => (
            <ExplanationCard
              key={`${item.term}-${index}`}
              item={item}
              sourceAvailable={sourceAvailable}
              onNavigateSource={onNavigateSource}
            />
          ))}
        </ul>
      )}
    </section>
  )
}
