import { memo, useId, type ReactNode } from 'react'
import { FileText, FileX2, LoaderCircle } from 'lucide-react'

import { cn } from '../lib/utils'
import type { DocumentSource } from '../review/sourceText'
import { Button } from './ui/Button'

interface SourceTextPanelProps {
  source: DocumentSource
  /** 원문 textarea의 id. 라벨을 프로그램적으로 붙이기 위해 부르는 쪽이 정한다(§11). */
  textareaId: string
  /**
   * 원문을 못 가져왔을 때 그 화면에서만 참인 덧말.
   *
   * 패널 자체의 문구는 화면을 가리지 않는다 — 진행 화면에는 대응표도 결과도 없으므로
   * "아래 대응표를 보라" 같은 말을 공통 문구에 넣으면 절반의 화면에서 거짓이 된다.
   */
  failureNote?: ReactNode
  rows?: number
  /**
   * 단위 목록 모드(DESIGN.md §6.4, 계획 §6 S3) — 결과 패널이 `SegmentedResultEditor`로
   * 그려질 때만 부르는 쪽이 원문을 `\n`으로 쪼갠 줄 목록을 넘긴다. 넘기면 큰 textarea
   * 하나 대신 **읽기 전용 단위마다 하나**로 그리고, hover·focus로 결과 패널과 하이라이트를
   * 주고받는다(클릭 가능 — 각 단위가 포커스를 받는 요소다).
   *
   * `undefined`면 지금까지처럼 원문 전체를 담은 단일 읽기 전용 textarea를 그린다 —
   * 대응표가 없거나(`segment_map: null`) 단위가 너무 많은 화면은 이 갈래를 그대로 쓴다.
   */
  units?: string[]
  /** 결과 패널에서 hover·focus 중인 쉬운 글 단위가 가리키는 원본 단위 색인들. */
  highlightedIndexes?: ReadonlySet<number>
  /** 사용자가 원본 단위를 hover·focus했을 때(벗어나면 `null`) 알린다. */
  onHoverUnit?: (index: number | null) => void
}

interface SourceUnitRowProps {
  index: number
  text: string
  highlighted: boolean
  onHoverUnit?: (index: number | null) => void
}

/**
 * 원본 패널의 단위 하나. `memo`로 감싼 이유는 결과 패널에서 hover할 때마다 원본 단위가
 * 최대 200개까지 함께 다시 그려지는 것을 막기 위해서다(LOW 리뷰) — `onHoverUnit`은
 * 부모가 넘기는 상태 setter라 참조가 안정적이므로, hover로 `highlighted`가 실제로
 * 바뀐 한두 행만 다시 그려진다.
 */
const SourceUnitRow = memo(function SourceUnitRow({
  index,
  text,
  highlighted,
  onHoverUnit,
}: SourceUnitRowProps) {
  return (
    <div role="listitem">
      <textarea
        aria-label={`원본 ${index + 1}번째 문단`}
        className={cn(
          'min-h-11 w-full resize-y rounded-[10px] border border-input bg-secondary px-3.5 py-2.5 text-[17px] leading-[1.75] text-foreground transition-colors motion-reduce:transition-none',
          highlighted && 'border-primary ring-2 ring-primary/40',
        )}
        value={text}
        rows={2}
        readOnly
        onFocus={() => onHoverUnit?.(index)}
        onBlur={() => onHoverUnit?.(null)}
        onMouseEnter={() => onHoverUnit?.(index)}
        onMouseLeave={() => onHoverUnit?.(null)}
      />
    </div>
  )
})

/** 실패 종류별 문구. 사용자가 **다음에 할 일이 다르므로** 같은 말로 뭉치지 않는다(§9). */
const FAILURE_TEXT = {
  not_found: {
    reason: '원문을 찾을 수 없습니다.',
    advice: '보관 기간이 지나 파기됐거나 지워진 문서입니다. 다시 불러와도 결과는 같습니다.',
  },
  unreachable: {
    reason: '원문을 불러오지 못했습니다.',
    advice: '네트워크 상태를 확인한 뒤 다시 불러와 주세요.',
  },
} as const

/**
 * 검수·진행 화면 왼쪽의 «원문» 패널.
 *
 * 두 화면이 같은 컴포넌트를 쓰는 이유는 문구를 한 곳에 두기 위해서다 — 원문이 없는
 * 이유를 화면마다 다르게 적으면 사용자는 같은 사실을 두 가지로 배운다.
 *
 * §9가 요구하는 대로 **로딩·실패·원문을 서로 다르게** 말한다. 특히 불러오는 중에
 * 「원문 없음」을 보여주지 않는다 — 그것은 아직 참이 아닌 문장이다. 판정은 이 컴포넌트가
 * 하지 않고 `useDocumentSource`가 준 상태를 그대로 그린다.
 *
 * **live region을 만들지 않는다.** 이 패널은 사용자의 조작에 대한 응답이 아니라 화면의
 * 본문이고, 진행 화면에는 이미 진행 상황을 알리는 `role="status"`가 하나 있다. 거기에
 * 하나를 더 얹으면 같은 화면에서 두 목소리가 겹친다(§11). 불러오는 중이라는 사실은
 * `aria-busy`로 알린다.
 *
 * 패널 바깥 상자(테두리·패딩·탭 패널 의미)는 부르는 화면이 그린다.
 */
export function SourceTextPanel({
  source,
  textareaId,
  failureNote,
  rows = 20,
  units,
  highlightedIndexes,
  onHoverUnit,
}: SourceTextPanelProps) {
  const listHeadingId = useId()

  if (source.state.status === 'loading') {
    return (
      <div aria-busy="true">
        <h2 className="mb-2 flex items-center gap-2 text-sm font-bold text-muted-foreground">
          <LoaderCircle
            className="size-[18px] shrink-0 animate-spin motion-reduce:animate-none"
            aria-hidden="true"
          />
          원문 불러오는 중
        </h2>
        <div className="rounded-[10px] border border-dashed border-input bg-background p-5">
          <p className="m-0 font-semibold">원문을 불러오고 있습니다…</p>
          <p className="field-hint mt-2">잠시만 기다려 주세요.</p>
        </div>
      </div>
    )
  }

  if (source.state.status === 'failed') {
    const { reason, advice } = FAILURE_TEXT[source.state.failure]
    return (
      <div>
        <h2 className="mb-2 flex items-center gap-2 text-sm font-bold text-muted-foreground">
          <FileX2 className="size-[18px] shrink-0" aria-hidden="true" />
          원문을 불러오지 못함
        </h2>
        <div className="rounded-[10px] border border-dashed border-input bg-background p-5">
          <p className="m-0 font-semibold">{reason}</p>
          <p className="field-hint mt-2">{advice}</p>
          {failureNote !== undefined && <p className="field-hint mt-2">{failureNote}</p>}
          {/* 다시 눌러 볼 값이 있을 때만 행동을 준다 — 404는 다시 물어도 404다(§15). */}
          {source.state.failure === 'unreachable' && (
            <Button type="button" variant="outline" className="mt-3" onClick={source.retry}>
              원문 다시 불러오기
            </Button>
          )}
        </div>
      </div>
    )
  }

  if (units !== undefined) {
    return (
      <>
        <h2
          className="mb-2 flex items-center gap-2 text-sm font-bold text-muted-foreground"
          id={listHeadingId}
        >
          <FileText className="size-[18px] shrink-0" aria-hidden="true" />
          원본 (읽기 전용)
        </h2>
        {/* 원본 단위마다 읽기 전용 textarea 하나(§6.4, 계획 §6 S3) — 결과 패널의
            `SegmentedResultEditor`와 짝을 이룬다. 큰 textarea 하나 대신 단위로 쪼개는
            이유는 hover·focus로 결과 쪽 단위와 서로 하이라이트를 주고받기 위해서다.
            textarea 자체가 이미 클릭·포커스를 받는 요소다("클릭 가능"). */}
        <div className="flex flex-col gap-2" role="list" aria-labelledby={listHeadingId}>
          {units.map((unit, index) => (
            <SourceUnitRow
              key={index}
              index={index}
              text={unit}
              highlighted={highlightedIndexes?.has(index) ?? false}
              onHoverUnit={onHoverUnit}
            />
          ))}
        </div>
      </>
    )
  }

  return (
    <>
      <h2 className="mb-2 flex items-center gap-2 text-sm font-bold text-muted-foreground">
        <FileText className="size-[18px] shrink-0" aria-hidden="true" />
        <label htmlFor={textareaId}>원본 (읽기 전용)</label>
      </h2>
      {/* 읽기 전용 textarea로 두면 키보드로 초점을 받아 스크롤·선택·복사까지 된다 —
          스크롤되는 div에 tabindex를 붙이는 것보다 조작 방법이 분명하다. 긴 원문(1만 자
          이상)도 이 상자 안에서만 스크롤되므로 페이지가 가로로 밀리지 않는다(§10). */}
      <textarea
        id={textareaId}
        className="review-textarea review-source text-[17px] leading-[1.75]"
        value={source.state.text}
        rows={rows}
        readOnly
      />
    </>
  )
}
