import { useEffect, useRef, type KeyboardEvent } from 'react'

import type { SegmentConfidence, SegmentMapUnit } from '../api/types'
import { cn } from '../lib/utils'
import { Badge } from './ui/Badge'

/**
 * 결과 패널이 문단(쉬운 글 단위) 목록으로 그려지는 상한.
 *
 * 이 값을 넘으면 `ReviewEditor`가 기존 단일 textarea로 내려앉힌다 — 계획
 * `docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md` §6 S3가 정한 값이다.
 * 파일럿 표본은 전부 이 상한 아래(≈50단위)라 일상 경로는 이 컴포넌트를 탄다.
 */
export const MAX_SEGMENTED_UNITS = 200

export interface SegmentedResultEditorProps {
  /** 결과 패널 제목(§6.4 "쉬운 글 결과 (고칠 수 있습니다)")의 id. `aria-labelledby`로 묶는다. */
  headingId: string
  /**
   * 쉬운 글 전체 문자열. 단위 목록은 이것을 `\n`으로 쪼갠 값이고, 어떤 편집(타이핑·분할·
   * 병합)을 해도 다시 이으면 이 값과 같은 왕복이 유지된다 — 저장(`saveReview`)은 이
   * 값을 그대로 보낸다.
   */
  value: string
  /** 단위 텍스트가 바뀔 때마다(타이핑·분할·병합 모두) 이은 문자열 전체로 부른다. */
  onChange: (next: string) => void
  /**
   * 지금 단위별 대응표. `value.split('\n')`과 같은 길이·순서라고 기대하지만, 서버
   * 응답이 아직 오지 않았거나 구조가 어긋난 자리는 「대응 확인 불가」로 안전하게 그린다.
   */
  unitMap: SegmentMapUnit[]
  /**
   * 분할·병합처럼 단위 구조 자체가 바뀔 때만 부른다. 단순 타이핑(단위 수 불변)은 대응이
   * 여전히 유효하다고 보고 이 콜백을 부르지 않는다 — 서버가 강제하지 않는 클라이언트
   * 재계산의 범위를 「구조가 바뀐 자리만」으로 좁힌 것이다(계약 `segment_map` 설명).
   */
  onUnitMapChange: (next: SegmentMapUnit[]) => void
  /** 결과 단위를 hover·focus했을 때 그 단위가 확인된(high) 원본 색인들을 알린다. */
  onHoverUnit: (sourceIndexes: number[]) => void
  /** 원본 패널 쪽에서 지금 hover·focus 중인 원본 단위 색인. 없으면 `null`. */
  hoveredSourceIndex: number | null
}

/** 신뢰도 배지 표현. 색만으로 가르지 않도록 문구를 함께 둔다(§8.1). */
const CONFIDENCE_BADGE: Record<SegmentConfidence, { label: string; tone: 'primary' | 'neutral' }> =
  {
    high: { label: '대응 확인', tone: 'primary' },
    low: { label: '추정', tone: 'neutral' },
  }

/**
 * 스크린리더 낭독용 단위 라벨. 예: "쉬운 글 단위 3, 원본 2번째 문단에 대응".
 *
 * `low`이거나 대응하는 원본 단위가 없으면 대응을 주장하지 않는다(계획 §2) — 화면은
 * `high`만 대응으로 말한다.
 */
function unitLabel(index: number, unit: SegmentMapUnit | undefined): string {
  const ordinal = index + 1
  if (unit === undefined || unit.confidence === 'low' || unit.source_unit_indexes.length === 0) {
    return `쉬운 글 단위 ${ordinal}, 대응 확인 불가`
  }
  const sourceOrdinals = unit.source_unit_indexes.map((sourceIndex) => sourceIndex + 1).join(', ')
  return `쉬운 글 단위 ${ordinal}, 원본 ${sourceOrdinals}번째 문단에 대응`
}

/** `easy_unit_index`를 배열 위치와 다시 맞춘다. 분할·병합 뒤 항상 이 함수를 거친다. */
function reindex(units: SegmentMapUnit[]): SegmentMapUnit[] {
  return units.map((unit, index) => ({ ...unit, easy_unit_index: index }))
}

/**
 * 검수 결과 패널 — 쉬운 글 단위마다 `<textarea>` 하나(DESIGN.md §6.4, 계획 §6 S3).
 *
 * **단위 나누기·합치기.** 단위 안에서 Enter는 그 자리를 나누고(뒤 단위 번호가 밀린다),
 * 맨 앞에서 Backspace는 앞 단위와 합친다. 지도(`unitMap`)는 서버 응답을 다시 받을
 * 때까지 이 국소적인 재계산만으로 유지된다 — 나뉜 두 단위는 원래 단위의 대응을 그대로
 * 물려받고, 합쳐진 단위는 두 원본 색인 합집합에 둘 다 `high`일 때만 `high`다.
 *
 * **하이라이트는 `high`일 때만 대응을 주장한다** — `low`는 「대응 확인 불가」로만
 * 표시하고 원본 패널과 서로 밝혀 주지 않는다(계획 §2).
 */
export function SegmentedResultEditor({
  headingId,
  value,
  onChange,
  unitMap,
  onUnitMapChange,
  onHoverUnit,
  hoveredSourceIndex,
}: SegmentedResultEditorProps) {
  const refs = useRef<Array<HTMLTextAreaElement | null>>([])
  const pendingFocusRef = useRef<{ index: number; caret: number } | null>(null)
  const units = value.split('\n')

  // 분할·병합 직후 캐럿을 논리적으로 이어지는 자리(나뉜 뒷부분의 시작, 합쳐진 경계)로
  // 옮긴다. DOM이 갱신된 다음 렌더에서만 대상 textarea가 존재하므로 effect에서 돈다.
  useEffect(() => {
    const pending = pendingFocusRef.current
    if (pending === null) {
      return
    }
    pendingFocusRef.current = null
    const target = refs.current[pending.index]
    if (target) {
      target.focus()
      target.setSelectionRange(pending.caret, pending.caret)
    }
  })

  function replaceUnit(index: number, text: string): void {
    const next = [...units]
    next[index] = text
    onChange(next.join('\n'))
  }

  function handleSplit(index: number, caret: number): void {
    const text = units[index] ?? ''
    const before = text.slice(0, caret)
    const after = text.slice(caret)
    const nextUnits = [...units.slice(0, index), before, after, ...units.slice(index + 1)]
    onChange(nextUnits.join('\n'))

    const original = unitMap[index]
    const carried = {
      source_unit_indexes: original?.source_unit_indexes ?? [],
      confidence: original?.confidence ?? ('low' as SegmentConfidence),
    }
    const nextMap = reindex([
      ...unitMap.slice(0, index),
      { easy_unit_index: 0, ...carried },
      { easy_unit_index: 0, ...carried },
      ...unitMap.slice(index + 1),
    ])
    onUnitMapChange(nextMap)

    pendingFocusRef.current = { index: index + 1, caret: 0 }
  }

  function handleMerge(index: number): void {
    if (index <= 0) {
      return
    }
    const prevText = units[index - 1] ?? ''
    const text = units[index] ?? ''
    const mergedCaret = prevText.length
    const nextUnits = [...units.slice(0, index - 1), prevText + text, ...units.slice(index + 1)]
    onChange(nextUnits.join('\n'))

    const a = unitMap[index - 1]
    const b = unitMap[index]
    const mergedIndexes = Array.from(
      new Set([...(a?.source_unit_indexes ?? []), ...(b?.source_unit_indexes ?? [])]),
    ).sort((left, right) => left - right)
    const mergedConfidence: SegmentConfidence =
      a?.confidence === 'high' && b?.confidence === 'high' ? 'high' : 'low'
    const nextMap = reindex([
      ...unitMap.slice(0, index - 1),
      { easy_unit_index: 0, source_unit_indexes: mergedIndexes, confidence: mergedConfidence },
      ...unitMap.slice(index + 1),
    ])
    onUnitMapChange(nextMap)

    pendingFocusRef.current = { index: index - 1, caret: mergedCaret }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>, index: number): void {
    const el = event.currentTarget
    const atStart = el.selectionStart === 0 && el.selectionEnd === 0
    const atEnd = el.selectionStart === el.value.length && el.selectionEnd === el.value.length

    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      handleSplit(index, el.selectionStart ?? el.value.length)
      return
    }
    if (event.key === 'Backspace' && atStart && index > 0) {
      event.preventDefault()
      handleMerge(index)
      return
    }
    if (event.key === 'ArrowUp' && atStart && index > 0) {
      event.preventDefault()
      const prev = refs.current[index - 1]
      prev?.focus()
      prev?.setSelectionRange(prev.value.length, prev.value.length)
      return
    }
    if (event.key === 'ArrowDown' && atEnd && index < units.length - 1) {
      event.preventDefault()
      const next = refs.current[index + 1]
      next?.focus()
      next?.setSelectionRange(0, 0)
    }
  }

  return (
    <div className="flex flex-col gap-2" role="group" aria-labelledby={headingId}>
      {units.map((unit, index) => {
        const mapUnit = unitMap[index]
        const confidence = mapUnit?.confidence
        const sourceIndexes = mapUnit?.source_unit_indexes ?? []
        const badge = confidence === undefined ? CONFIDENCE_BADGE.low : CONFIDENCE_BADGE[confidence]
        const highlighted =
          confidence === 'high' &&
          hoveredSourceIndex !== null &&
          sourceIndexes.includes(hoveredSourceIndex)

        return (
          <div key={index} className="flex flex-col gap-1">
            <Badge tone={badge.tone} withIcon={false} className="self-start" aria-hidden="true">
              {badge.label}
            </Badge>
            <textarea
              ref={(node) => {
                refs.current[index] = node
              }}
              aria-label={unitLabel(index, mapUnit)}
              className={cn(
                'min-h-11 w-full resize-y rounded-[10px] border border-input bg-card px-3.5 py-2.5 text-[17px] leading-[1.75] text-foreground transition-colors motion-reduce:transition-none',
                highlighted && 'border-primary ring-2 ring-primary/40',
              )}
              value={unit}
              rows={2}
              onChange={(event) => replaceUnit(index, event.target.value)}
              onKeyDown={(event) => handleKeyDown(event, index)}
              onFocus={() => onHoverUnit(confidence === 'high' ? sourceIndexes : [])}
              onBlur={() => onHoverUnit([])}
              onMouseEnter={() => onHoverUnit(confidence === 'high' ? sourceIndexes : [])}
              onMouseLeave={() => onHoverUnit([])}
            />
          </div>
        )
      })}
    </div>
  )
}
