import { memo, useCallback, useEffect, useRef, type KeyboardEvent } from 'react'

import type { SegmentConfidence, SegmentMapUnit } from '../api/types'
import { cn } from '../lib/utils'
import { Badge } from './ui/Badge'
import { ReconvertCandidateCard } from './ReconvertCandidateCard'

/**
 * 재변환 후보 카드가 그려질 때 이 컴포넌트가 필요로 하는 것(계획 §4 결정 3, §6 S5).
 * 후보를 만들고 채택하는 로직 자체는 `ReviewEditor`가 쥐고, 이 컴포넌트는 어느 단위
 * 아래에 카드를 앉힐지만 안다(`anchorEasyUnitIndex`).
 */
export interface SegmentedResultEditorCandidate {
  sourceUnitIndex: number
  candidateText: string
  mode: 'replace' | 'insert'
  /** 카드를 이 단위 바로 아래에 그린다. 지금 단위 범위 밖이면 카드를 그리지 않는다. */
  anchorEasyUnitIndex: number
}

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
  /**
   * 저장·내려받기가 도는 동안(§MEDIUM 리뷰) 편집을 막는다. 저장 버튼이 같은 동안
   * `disabled`가 되는 것과 같은 규칙이다 — 응답이 도착하기 전에 이어서 고치면
   * `persistDraft`가 그 사이의 수정을 지울 여지가 생긴다.
   */
  disabled?: boolean
  /**
   * 결과 단위가 focus를 잃을 때(계획 §6 S5) — 재변환 「이 위치에 넣기」가 참고하는
   * "마지막으로 초점이 있던 쉬운 글 단위와 그 캐럿"을 부르는 쪽이 기록하는 자리다.
   * 다시 변환 버튼(원본 패널)을 누르는 순간 지금 초점이 있던 결과 textarea가 이
   * 콜백으로 자기 위치를 남기고 초점을 잃는다.
   */
  onUnitBlur?: (index: number, caret: number) => void
  /** 재변환 후보 카드(계획 §4 결정 3, §6 S5). 있으면 `anchorEasyUnitIndex` 단위 바로
   * 아래에 그린다. */
  candidate?: SegmentedResultEditorCandidate | null
  /** 카드의 두 실행 버튼(바꾸기·이 위치에 넣기)을 함께 잠근다. */
  candidateDisabled?: boolean
  onCandidateReplace?: () => void
  onCandidateInsert?: () => void
  onCandidateClose?: () => void
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
 * `unitMap`이 `units`와 길이가 다르면 안전하게 맞춘다.
 *
 * 이 컴포넌트가 지도를 실제로 읽는 곳은 여기 한 곳뿐이다 — `units.length === unitMap.length`
 * 라는 구조적 불변식을 이 함수 하나로 강제한다(CRITICAL 리뷰). 분할·병합·여러 줄 입력
 * 처리는 항상 같은 길이로 지도를 다시 짜서 넘기므로 이 갈래는 보통 그대로 통과하고,
 * 서버 응답이 아직 오지 않았거나 예상 밖의 경합이 남긴 낡은 지도를 만났을 때만 자리를
 * 채운다 — 어긋난 옛 항목을 엉뚱한 단위에 잘못 붙이지 않고 「대응 확인 불가」로 둔다.
 */
function alignUnitMap(map: SegmentMapUnit[], unitCount: number): SegmentMapUnit[] {
  if (map.length === unitCount) {
    return map
  }
  return Array.from({ length: unitCount }, (_, index) => {
    const existing = map[index]
    return (
      existing ?? {
        easy_unit_index: index,
        source_unit_indexes: [],
        confidence: 'low' as SegmentConfidence,
      }
    )
  }).map((unit, index) => ({ ...unit, easy_unit_index: index }))
}

interface UnitRowProps {
  index: number
  text: string
  label: string
  badge: { label: string; tone: 'primary' | 'neutral' }
  highlighted: boolean
  disabled: boolean
  hoverSourceIndexes: number[]
  onTextChange: (index: number, text: string) => void
  onKeyDown: (event: KeyboardEvent<HTMLTextAreaElement>, index: number) => void
  onHoverUnit: (sourceIndexes: number[]) => void
  setRef: (index: number, node: HTMLTextAreaElement | null) => void
  onBlurUnit?: (index: number, caret: number) => void
}

/**
 * 결과 패널의 단위 하나. `memo`로 감싼 이유는 hover 한 번에 단위가 최대 200개까지 함께
 * 다시 그려지는 것을 막기 위해서다(LOW 리뷰). 부모가 넘기는 콜백들(`onTextChange`·
 * `onKeyDown`·`setRef`)이 `useCallback`으로 고정돼 있으므로, hover만 바뀐 렌더에서는
 * 이 행의 props가 실제로 달라진 행(밝혀지거나 밝혀짐이 풀린 한두 개)만 다시 그려진다.
 */
const UnitRow = memo(function UnitRow({
  index,
  text,
  label,
  badge,
  highlighted,
  disabled,
  hoverSourceIndexes,
  onTextChange,
  onKeyDown,
  onHoverUnit,
  setRef,
  onBlurUnit,
}: UnitRowProps) {
  return (
    <div className="flex flex-col gap-1">
      <Badge tone={badge.tone} withIcon={false} className="self-start" aria-hidden="true">
        {badge.label}
      </Badge>
      <textarea
        ref={(node) => setRef(index, node)}
        aria-label={label}
        // 사전 팝업(TermLookupPopover)이 선택이 일어난 textarea를 어느 단위인지
        // 되짚는 유일한 표식이다 — 그 팝업은 이 단위 배열을 몰라도 이 속성만으로
        // 전체 draft에서 이 단위의 자리를 찾아 치환할 수 있다.
        data-unit-index={index}
        className={cn(
          'min-h-11 w-full resize-y rounded-[10px] border border-input bg-card px-3.5 py-2.5 text-[17px] leading-[1.75] text-foreground transition-colors motion-reduce:transition-none',
          highlighted && 'border-primary ring-2 ring-primary/40',
        )}
        value={text}
        rows={2}
        disabled={disabled}
        onChange={(event) => onTextChange(index, event.target.value)}
        onKeyDown={(event) => onKeyDown(event, index)}
        onFocus={() => onHoverUnit(hoverSourceIndexes)}
        onBlur={(event) => {
          onHoverUnit([])
          // 재변환 「이 위치에 넣기」(계획 §6 S5)가 참고하는 마지막 활성 단위·캐럿을
          // 남긴다 — 다시 변환 버튼(원본 패널)을 누르는 순간 이 textarea가 초점을
          // 잃으므로 그 값을 여기서 붙든다.
          onBlurUnit?.(index, event.target.selectionStart ?? event.target.value.length)
        }}
        onMouseEnter={() => onHoverUnit(hoverSourceIndexes)}
        onMouseLeave={() => onHoverUnit([])}
      />
    </div>
  )
})

/**
 * 검수 결과 패널 — 쉬운 글 단위마다 `<textarea>` 하나(DESIGN.md §6.4, 계획 §6 S3).
 *
 * **단위 나누기·합치기.** 단위 안에서 Enter는 그 자리를 나누고(뒤 단위 번호가 밀린다),
 * 맨 앞에서 Backspace는 앞 단위와 합친다. 지도(`unitMap`)는 서버 응답을 다시 받을
 * 때까지 이 국소적인 재계산만으로 유지된다 — 나뉜 두 단위는 원래 단위의 대응을 그대로
 * 물려받고, 합쳐진 단위는 두 원본 색인 합집합에 둘 다 `high`일 때만 `high`다.
 *
 * **Shift+Enter·붙여넣기·드롭도 같은 규칙을 탄다(CRITICAL 리뷰).** 브라우저는 이 셋 모두
 * `\n`이 이미 섞인 값을 `onChange`로 준다 — `units = value.split('\n')`은 그만큼
 * 늘어나는데 지도를 그대로 두면 그 뒤 모든 단위가 엉뚱한 지도 항목을 입는다. 그래서
 * 값 하나에 `\n`이 있으면 그 자리에서 나뉘는 것으로 보고(`handleUnitTextChange`)
 * `handleSplit`과 같은 방식으로 지도를 함께 다시 짠다: 첫 조각만 원래 단위의 대응을
 * 물려받고, 새로 생긴 나머지 조각은 무엇에 대응하는지 알 수 없으므로 `low`·빈 배열로
 * 안전하게 둔다.
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
  disabled = false,
  onUnitBlur,
  candidate = null,
  candidateDisabled = false,
  onCandidateReplace,
  onCandidateInsert,
  onCandidateClose,
}: SegmentedResultEditorProps) {
  const refs = useRef<Array<HTMLTextAreaElement | null>>([])
  const pendingFocusRef = useRef<{ index: number; caret: number } | null>(null)
  const units = value.split('\n')
  const safeUnitMap = alignUnitMap(unitMap, units.length)

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

  const setUnitRef = useCallback((index: number, node: HTMLTextAreaElement | null) => {
    refs.current[index] = node
  }, [])

  const handleSplit = useCallback(
    (index: number, caret: number) => {
      const currentUnits = value.split('\n')
      const text = currentUnits[index] ?? ''
      const before = text.slice(0, caret)
      const after = text.slice(caret)
      const nextUnits = [
        ...currentUnits.slice(0, index),
        before,
        after,
        ...currentUnits.slice(index + 1),
      ]
      onChange(nextUnits.join('\n'))

      const currentMap = alignUnitMap(unitMap, currentUnits.length)
      const original = currentMap[index]
      const inheritedConfidence = original?.confidence ?? ('low' as SegmentConfidence)
      // 두 새 단위는 각자 자기 배열을 갖는다(LOW 리뷰) — `original.source_unit_indexes`를
      // 그대로 공유하면 훗날 한쪽을 고치는 코드가 다른 쪽까지 조용히 바꿔 버린다.
      const nextMap = reindex([
        ...currentMap.slice(0, index),
        {
          easy_unit_index: 0,
          source_unit_indexes: [...(original?.source_unit_indexes ?? [])],
          confidence: inheritedConfidence,
        },
        {
          easy_unit_index: 0,
          source_unit_indexes: [...(original?.source_unit_indexes ?? [])],
          confidence: inheritedConfidence,
        },
        ...currentMap.slice(index + 1),
      ])
      onUnitMapChange(nextMap)

      pendingFocusRef.current = { index: index + 1, caret: 0 }
    },
    [value, unitMap, onChange, onUnitMapChange],
  )

  const handleMerge = useCallback(
    (index: number) => {
      if (index <= 0) {
        return
      }
      const currentUnits = value.split('\n')
      const prevText = currentUnits[index - 1] ?? ''
      const text = currentUnits[index] ?? ''
      const mergedCaret = prevText.length
      const nextUnits = [
        ...currentUnits.slice(0, index - 1),
        prevText + text,
        ...currentUnits.slice(index + 1),
      ]
      onChange(nextUnits.join('\n'))

      const currentMap = alignUnitMap(unitMap, currentUnits.length)
      const a = currentMap[index - 1]
      const b = currentMap[index]
      const mergedIndexes = Array.from(
        new Set([...(a?.source_unit_indexes ?? []), ...(b?.source_unit_indexes ?? [])]),
      ).sort((left, right) => left - right)
      const mergedConfidence: SegmentConfidence =
        a?.confidence === 'high' && b?.confidence === 'high' ? 'high' : 'low'
      const nextMap = reindex([
        ...currentMap.slice(0, index - 1),
        { easy_unit_index: 0, source_unit_indexes: mergedIndexes, confidence: mergedConfidence },
        ...currentMap.slice(index + 1),
      ])
      onUnitMapChange(nextMap)

      pendingFocusRef.current = { index: index - 1, caret: mergedCaret }
    },
    [value, unitMap, onChange, onUnitMapChange],
  )

  /**
   * 단위 하나의 값이 바뀔 때 부르는 유일한 진입점.
   *
   * 값에 `\n`이 없으면(보통의 타이핑) 단위 수가 그대로이므로 지도를 건드리지 않는다.
   * `\n`이 있으면(Shift+Enter·붙여넣기·드롭) `handleSplit`과 같은 방식으로 그 자리에서
   * 나뉘는 것으로 보고 지도를 함께 다시 짠다 — 위 컴포넌트 문서의 CRITICAL 리뷰 항목.
   */
  const handleUnitTextChange = useCallback(
    (index: number, text: string) => {
      const currentUnits = value.split('\n')
      if (!text.includes('\n')) {
        const nextUnits = [...currentUnits]
        nextUnits[index] = text
        onChange(nextUnits.join('\n'))
        return
      }

      const parts = text.split('\n')
      const nextUnits = [
        ...currentUnits.slice(0, index),
        ...parts,
        ...currentUnits.slice(index + 1),
      ]
      onChange(nextUnits.join('\n'))

      const currentMap = alignUnitMap(unitMap, currentUnits.length)
      const original = currentMap[index]
      const inserted: SegmentMapUnit[] = parts.map((_, partIndex) =>
        partIndex === 0
          ? {
              easy_unit_index: 0,
              source_unit_indexes: [...(original?.source_unit_indexes ?? [])],
              confidence: original?.confidence ?? ('low' as SegmentConfidence),
            }
          : { easy_unit_index: 0, source_unit_indexes: [], confidence: 'low' as SegmentConfidence },
      )
      const nextMap = reindex([
        ...currentMap.slice(0, index),
        ...inserted,
        ...currentMap.slice(index + 1),
      ])
      onUnitMapChange(nextMap)
    },
    [value, unitMap, onChange, onUnitMapChange],
  )

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLTextAreaElement>, index: number) => {
      const el = event.currentTarget
      const atStart = el.selectionStart === 0 && el.selectionEnd === 0
      const atEnd = el.selectionStart === el.value.length && el.selectionEnd === el.value.length
      const unitCount = value.split('\n').length

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
      if (event.key === 'ArrowDown' && atEnd && index < unitCount - 1) {
        event.preventDefault()
        const next = refs.current[index + 1]
        next?.focus()
        next?.setSelectionRange(0, 0)
      }
    },
    [value, handleSplit, handleMerge],
  )

  return (
    <div className="flex flex-col gap-2" role="group" aria-labelledby={headingId}>
      {units.map((unit, index) => {
        const mapUnit = safeUnitMap[index]
        const confidence = mapUnit?.confidence
        const sourceIndexes = mapUnit?.source_unit_indexes ?? []
        const highlighted =
          confidence === 'high' &&
          hoveredSourceIndex !== null &&
          sourceIndexes.includes(hoveredSourceIndex)
        const badge = confidence === undefined ? CONFIDENCE_BADGE.low : CONFIDENCE_BADGE[confidence]

        return (
          <div key={index} className="flex flex-col gap-2">
            <UnitRow
              index={index}
              text={unit}
              label={unitLabel(index, mapUnit)}
              badge={badge}
              highlighted={highlighted}
              disabled={disabled}
              hoverSourceIndexes={confidence === 'high' ? sourceIndexes : []}
              onTextChange={handleUnitTextChange}
              onKeyDown={handleKeyDown}
              onHoverUnit={onHoverUnit}
              setRef={setUnitRef}
              onBlurUnit={onUnitBlur}
            />
            {/* 재변환 후보 카드(계획 §4 결정 3, §6 S5) — 이 단위 바로 아래에 앉힌다. */}
            {candidate !== null && candidate.anchorEasyUnitIndex === index && (
              <ReconvertCandidateCard
                sourceUnitIndex={candidate.sourceUnitIndex}
                candidateText={candidate.candidateText}
                mode={candidate.mode}
                disabled={candidateDisabled}
                onReplace={() => onCandidateReplace?.()}
                onInsert={() => onCandidateInsert?.()}
                onClose={() => onCandidateClose?.()}
              />
            )}
          </div>
        )
      })}
    </div>
  )
}
