import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'

import { Button } from './ui/Button'

export interface ReconvertCandidateCardProps {
  /** 0 기반 원본 단위 색인 — 카드 제목·접근 가능한 이름에 쓴다. */
  sourceUnitIndex: number
  candidateText: string
  /**
   * `replace`는 `high` 대응 + 단일 쉬운 글 단위 + 지문 불변일 때만이다(계획 §4 결정 3)
   * — 이때만 「바꾸기」를 보여준다. 그 밖의 모든 경우(`insert`)는 「이 위치에 넣기」만
   * 보여준다 — 자동 교체를 어떤 경우에도 하지 않기 위해서다.
   */
  mode: 'replace' | 'insert'
  /** 두 실행 버튼을 함께 잠근다(다른 재변환이 진행 중이거나 저장 중일 때). */
  disabled?: boolean
  onReplace: () => void
  onInsert: () => void
  onClose: () => void
}

/**
 * 재변환 후보 카드(계획 §4 결정 3, §6 S5).
 *
 * **서버 응답은 후보 텍스트뿐이고 어떤 경우에도 자동으로 결과를 갈아 끼우지 않는다.**
 * 이 카드가 그 사실을 담당자에게 보여주는 유일한 자리다 — `replace`는 이미 확인된
 * 1:1 대응을 사람이 한 번 더 확인하고 누르는 것이고, `insert`는 위치를 사람이 직접
 * 확정하는 것이다.
 */
export function ReconvertCandidateCard({
  sourceUnitIndex,
  candidateText,
  mode,
  disabled = false,
  onReplace,
  onInsert,
  onClose,
}: ReconvertCandidateCardProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  // 카드가 뜨는 순간 초점을 카드로 옮긴다(MEDIUM 리뷰 3). 재변환 버튼을 누른 뒤에도
  // 초점이 그 버튼(원본 패널)에 그대로 남아 있으면 방금 도착한 후보를 화면을 보지
  // 않는 한 알아챌 수 없다.
  useEffect(() => {
    containerRef.current?.focus()
  }, [])

  return (
    <div
      ref={containerRef}
      tabIndex={-1}
      role="region"
      aria-label={`원본 ${sourceUnitIndex + 1}번째 문단 재변환 후보`}
      className="rounded-[10px] border border-primary/40 bg-accent p-3"
    >
      {/* 카드가 도착했다는 사실 자체를 낭독한다(MEDIUM 리뷰 3) — 위 초점 이동만으로는
          카드가 뭔지(바꾸기/삽입) 미리 알리지 못한다. `role="status"`는 `aria-live="polite"`와
          같다. */}
      <p role="status" className="m-0 mb-2 text-xs font-semibold text-primary">
        재변환 후보가 도착했습니다.{' '}
        {mode === 'replace' ? '바꿀지 확인해 주세요.' : '넣을 위치를 확인해 주세요.'}
      </p>
      <div className="mb-2 flex items-start justify-between gap-2">
        <p className="m-0 text-[15px] leading-[1.6] whitespace-pre-wrap text-foreground">
          {candidateText}
        </p>
        <button
          type="button"
          aria-label="재변환 후보 닫기"
          className="flex size-11 shrink-0 items-center justify-center rounded-full text-muted-foreground hover:bg-secondary hover:text-foreground"
          onClick={onClose}
        >
          <X className="size-[18px]" aria-hidden="true" />
        </button>
      </div>
      <div className="flex flex-wrap gap-2">
        {mode === 'replace' ? (
          <Button type="button" size="sm" className="h-11" disabled={disabled} onClick={onReplace}>
            바꾸기
          </Button>
        ) : (
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-11"
            disabled={disabled}
            onClick={onInsert}
          >
            이 위치에 넣기
          </Button>
        )}
      </div>
    </div>
  )
}
