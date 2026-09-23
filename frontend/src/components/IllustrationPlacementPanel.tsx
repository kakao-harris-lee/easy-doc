import { useEffect, useId, useMemo, useRef, useState } from 'react'

import {
  ApiError,
  getIllustrationPlacements,
  getIllustrations,
  illustrationImageUrl,
  putIllustrationPlacements,
} from '../api/client'
import type { Illustration, IllustrationPlacement } from '../api/types'
import { Button } from './ui/Button'

interface IllustrationPlacementPanelProps {
  conversionId: string
  /** 본문 저장과 공유하는 서버 관리 버전(CAS) — `expected_content_revision`으로 그대로 보낸다. */
  contentRevision: number
  /** 저장하지 않은 본문 수정이 있는지. 있으면 배치 저장을 막는다(본문과 줄 색인이 어긋난다). */
  dirty: boolean
  /** 현재 본문 줄 배열(`draft.split('\n')`). `easy_unit_index`는 이 배열의 0-based 색인이다. */
  units: string[]
  /** 저장 직후와 최초 조회 직후 모두, 저장된 배치 개수와 stale 여부를 부모에 알린다. */
  onPlacementsChange?: (count: number, stale: boolean) => void
}

const ALWAYS_NOTICE = '그림은 웹 미리보기에만 보입니다. DOCX·HWPX·TXT 파일에는 들어가지 않습니다.'
const DIRTY_NOTICE = '본문을 먼저 저장한 뒤 그림을 배치해 주세요.'
const STALE_BADGE_TEXT = '현재 본문과 다름'
const STALE_NOTICE = '본문이 바뀌었습니다. 그림 위치를 확인한 뒤 다시 저장해 주세요.'
const STALE_PREVIEW_NOTICE = '본문이 바뀌어 그림을 숨겼습니다'
// ReviewEditor의 기존 409 복구 배너 첫 줄과 같은 문구다 — 같은 상황을 다른 말로 설명하지 않는다.
const CONTENT_CONFLICT_MESSAGE = '다른 화면에서 저장한 최신 내용과 충돌했습니다.'
const LOAD_ERROR_FALLBACK = '그림 배치를 불러오지 못했습니다. 다시 시도해 주세요.'
const SAVE_ERROR_FALLBACK = '그림 배치를 저장하지 못했습니다. 다시 시도해 주세요.'
const MAX_LINE_PREVIEW_LENGTH = 60
/** 계약 `x-input-limits.max_illustration_placements` — 변환 한 건당 최대 그림 배치 수. */
const MAX_PLACEMENTS = 10

function describeError(caught: unknown, fallback: string): string {
  if (caught instanceof ApiError) {
    return caught.status === 409 ? CONTENT_CONFLICT_MESSAGE : caught.message
  }
  return fallback
}

/**
 * ER-16 — 검수된 그림을 본문 줄에 배치하고 웹 미리보기로 확인한다.
 *
 * 그림은 DOCX·HWPX·TXT 파일 출력에는 담기지 않는다(AC-R7-b) — 그 사실을 안내문으로 늘
 * 먼저 말한다. 배치는 본문 저장과 같은 CAS(`expected_content_revision`)를 쓴다 — 본문이
 * 먼저 저장돼 있어야(`!dirty`) 줄 색인이 어긋나지 않는다.
 */
export function IllustrationPlacementPanel({
  conversionId,
  contentRevision,
  dirty,
  units,
  onPlacementsChange,
}: IllustrationPlacementPanelProps) {
  const headingId = useId()
  const previewHeadingId = useId()
  const [catalog, setCatalog] = useState<Illustration[]>([])
  const [serverPlacements, setServerPlacements] = useState<IllustrationPlacement[]>([])
  const [stale, setStale] = useState(false)
  const [selections, setSelections] = useState<Record<number, string>>({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const requestVersion = useRef(0)
  // effect 재실행 없이 항상 최신 콜백을 부르기 위한 참조 — ExplanationsPanel과 달리 이
  // 패널은 로드뿐 아니라 저장 성공 때도 같은 동기화 함수를 부른다. 렌더 중에는 ref를
  // 쓰지 않는다(react-hooks/refs) — 매 렌더 뒤 effect로 동기화한다.
  const onPlacementsChangeRef = useRef(onPlacementsChange)
  useEffect(() => {
    onPlacementsChangeRef.current = onPlacementsChange
  })

  function applyPlacements(placements: IllustrationPlacement[], nextStale: boolean): void {
    setServerPlacements(placements)
    setStale(nextStale)
    const seeded: Record<number, string> = {}
    placements.forEach((placement) => {
      seeded[placement.easy_unit_index] = placement.asset_id
    })
    setSelections(seeded)
    onPlacementsChangeRef.current?.(placements.length, nextStale)
  }

  useEffect(() => {
    const version = requestVersion.current + 1
    requestVersion.current = version
    const controller = new AbortController()
    async function load(): Promise<void> {
      setLoading(true)
      setLoadError(null)
      try {
        const [catalogResponse, placementsResponse] = await Promise.all([
          getIllustrations(controller.signal),
          getIllustrationPlacements(conversionId, controller.signal),
        ])
        if (controller.signal.aborted || requestVersion.current !== version) return
        setCatalog(catalogResponse.illustrations)
        applyPlacements(placementsResponse.placements, placementsResponse.stale)
      } catch (caught) {
        if (controller.signal.aborted || requestVersion.current !== version) return
        setLoadError(describeError(caught, LOAD_ERROR_FALLBACK))
      } finally {
        if (!controller.signal.aborted && requestVersion.current === version) setLoading(false)
      }
    }
    void load()

    return () => controller.abort()
  }, [conversionId, contentRevision])

  const catalogByAssetId = useMemo(
    () => new Map(catalog.map((item) => [item.asset_id, item])),
    [catalog],
  )

  const outOfRangePlacements = useMemo(
    () => serverPlacements.filter((placement) => placement.easy_unit_index >= units.length),
    [serverPlacements, units.length],
  )

  const selectedCount = useMemo(
    () => Object.values(selections).filter((assetId) => assetId !== '').length,
    [selections],
  )
  const overLimit = selectedCount > MAX_PLACEMENTS

  function handleSelectionChange(index: number, assetId: string): void {
    setSelections((current) => ({ ...current, [index]: assetId }))
  }

  async function handleSave(): Promise<void> {
    setSaving(true)
    setSaveError(null)
    try {
      // 본문에 더는 없는 줄(index >= units.length)은 여기서 걸러진다 — stale 표시로만
      // 안내하고, 저장에는 실어 보내지 않는다.
      const placements: IllustrationPlacement[] = Object.entries(selections)
        .filter(([indexKey, assetId]) => assetId !== '' && Number(indexKey) < units.length)
        .map(([indexKey, assetId]) => ({ easy_unit_index: Number(indexKey), asset_id: assetId }))
      const response = await putIllustrationPlacements(conversionId, {
        expected_content_revision: contentRevision,
        placements,
      })
      applyPlacements(response.placements, response.stale)
    } catch (caught) {
      setSaveError(describeError(caught, SAVE_ERROR_FALLBACK))
    } finally {
      setSaving(false)
    }
  }

  const saveDisabled = dirty || saving || loading || overLimit

  return (
    <section
      className="mt-6 rounded-xl border border-border bg-card p-4 sm:p-6"
      aria-labelledby={headingId}
      aria-busy={loading || undefined}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold">
          그림 배치
        </h2>
        {stale && <span className="text-sm font-semibold text-warning">{STALE_BADGE_TEXT}</span>}
      </div>
      <p className="mt-1 text-sm text-muted-foreground">{ALWAYS_NOTICE}</p>

      {dirty && (
        <p className="mt-3 rounded-[10px] border border-warning/25 bg-warning-surface p-3 text-sm text-warning">
          {DIRTY_NOTICE}
        </p>
      )}
      {stale && (
        <p className="mt-3 rounded-[10px] border border-warning/25 bg-warning-surface p-3 text-sm text-warning">
          {STALE_NOTICE}
        </p>
      )}

      {loading && (
        <p className="mt-5" role="status" aria-busy="true">
          그림 배치를 불러오는 중…
        </p>
      )}
      {loadError !== null && (
        <div className="form-error mt-4" role="alert">
          {loadError}
        </div>
      )}

      {!loading && loadError === null && (
        <>
          <p className="mt-4 text-sm text-muted-foreground">
            배치 {selectedCount} / {MAX_PLACEMENTS}
          </p>
          {overLimit && (
            <p className="mt-2 text-sm font-semibold text-warning" role="status">
              그림은 최대 {MAX_PLACEMENTS}개까지 배치할 수 있습니다. (현재 {selectedCount}개)
            </p>
          )}

          <ul className="mt-5 flex flex-col gap-2">
            {units.map((text, index) => {
              const displayText = text === '' ? '(빈 줄)' : text.slice(0, MAX_LINE_PREVIEW_LENGTH)
              return (
                <li
                  key={index}
                  className="flex flex-col gap-2 rounded-[10px] border border-border p-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="flex items-baseline gap-2 sm:flex-1">
                    <span className="shrink-0 text-sm font-semibold text-muted-foreground">
                      {index + 1}번째 줄
                    </span>
                    <span className="text-sm text-foreground [overflow-wrap:anywhere]">
                      {displayText}
                    </span>
                  </div>
                  <select
                    aria-label={`${index + 1}번째 줄 그림`}
                    className="min-h-11 rounded-md border border-input bg-card px-2 text-sm sm:w-48"
                    value={selections[index] ?? ''}
                    disabled={saving}
                    onChange={(event) => handleSelectionChange(index, event.target.value)}
                  >
                    <option value="">그림 없음</option>
                    {catalog.map((item) => (
                      <option key={item.asset_id} value={item.asset_id}>
                        {item.caption}
                      </option>
                    ))}
                  </select>
                </li>
              )
            })}
          </ul>

          {outOfRangePlacements.length > 0 && (
            <ul className="mt-3 flex flex-col gap-1">
              {outOfRangePlacements.map((placement) => (
                <li key={placement.easy_unit_index} className="text-sm text-muted-foreground">
                  {placement.easy_unit_index + 1}번째 줄 — 본문에 없는 줄(저장 시 제외)
                </li>
              ))}
            </ul>
          )}

          {saveError !== null && (
            <div className="form-error mt-4" role="alert">
              {saveError}
            </div>
          )}

          <div className="mt-4">
            <Button
              type="button"
              variant="primary"
              className="min-h-11"
              disabled={saveDisabled}
              loading={saving}
              onClick={() => void handleSave()}
            >
              {saving ? '저장하는 중…' : '그림 배치 저장'}
            </Button>
          </div>

          {(stale || serverPlacements.length > 0) && (
            <section className="mt-6" aria-labelledby={previewHeadingId}>
              <h3 id={previewHeadingId} className="text-base font-semibold">
                미리보기
              </h3>
              {stale ? (
                <p className="mt-2 text-sm text-muted-foreground">{STALE_PREVIEW_NOTICE}</p>
              ) : (
                <div className="mt-3 flex flex-col gap-3">
                  {units.map((text, index) => {
                    const placement = serverPlacements.find((p) => p.easy_unit_index === index)
                    const item = placement ? catalogByAssetId.get(placement.asset_id) : undefined
                    return (
                      <div key={index}>
                        {item && (
                          <figure className="flex flex-col items-start gap-1">
                            <img
                              src={illustrationImageUrl(item.image_url)}
                              alt={item.alt_text}
                              width={64}
                              height={64}
                              className="size-16 rounded-[6px] border border-border object-contain"
                            />
                            <figcaption className="text-xs text-muted-foreground">
                              {item.caption}
                            </figcaption>
                          </figure>
                        )}
                        <p className="text-sm text-foreground">{text}</p>
                      </div>
                    )
                  })}
                </div>
              )}
            </section>
          )}
        </>
      )}
    </section>
  )
}
