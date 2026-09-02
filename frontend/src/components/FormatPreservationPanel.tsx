import { useId } from 'react'
import { Check, HelpCircle, Info, XCircle, type LucideIcon } from 'lucide-react'

import type {
  ConversionResponse,
  FormatPreservation,
  FormatPreservationStatus,
  SourceFormat,
} from '../api/types'
import { Badge } from './ui/Badge'

/**
 * 상태 한 줄의 표현.
 *
 * 색은 여기서 **혼자 일하지 않는다**(DESIGN.md §8.1) — 라벨 문구와 아이콘이 늘 함께 붙는다.
 *
 * `available`과 `partial`은 **둘 다 정상 결과다.** 이 제품이 약속하는 서식 유지는 「심하게
 * 틀어지지 않는 정도」이고, 문단 수가 조금 어긋나는 것은 예상된 결과이지 사용자가 고칠
 * 문제가 아니다 — 다시 시도해도 같은 답이 온다. 그래서 두 상태 모두 경고색·경고
 * 아이콘을 쓰지 않고 담담한 계열로 둔다. 조치할 수 없는 정보를 경고처럼 그리면 소음이다.
 * 실제 문제는 `failed` 하나뿐이라 강조는 그쪽에만 몬다.
 */
interface StatusView {
  tone: 'success' | 'neutral' | 'danger' | 'info'
  icon: LucideIcon
  label: string
}

const STATUS_VIEWS: Record<FormatPreservationStatus, StatusView> = {
  available: { tone: 'success', icon: Check, label: '유지 가능' },
  // 정상 계열이다 — `available`과 다른 것은 라벨·아이콘과 아래 `details` 한 줄뿐이다.
  partial: { tone: 'neutral', icon: Info, label: '일부 유지' },
  failed: { tone: 'danger', icon: XCircle, label: '서식 유지 실패' },
  // 패널을 그리지 않는 상태다. 표에 남겨 두는 것은 값 집합이 넷임을 타입으로 지키려는 것뿐.
  not_applicable: { tone: 'neutral', icon: Info, label: '적용 대상 아님' },
}

/**
 * 서버가 아직 판정하지 않은 상태(`format_preservation === null`).
 *
 * 「유지 가능」도 「유지 불가」도 아니라고만 말한다. 스피너를 두지 않는다 — 판정은 조회 한
 * 번 안에서 끝나므로 기다릴 진행이 없고, 도는 표식은 곧 끝날 일을 약속하게 된다.
 */
const UNKNOWN_VIEW: StatusView = { tone: 'info', icon: HelpCircle, label: '확인되지 않음' }

/**
 * 서식 유지 실패에서 사용자가 할 수 있는 일(§6.5, §9).
 *
 * 무슨 일이 일어났는지는 서버 `details`가 말한다. 여기서는 **보존된 것**과 **다시 할 수
 * 있는 일**만 덧붙인다. 다른 형식으로 대신 받는 우회는 제시하지 않는다 — §6.5가 금지한
 * 「텍스트 전용 파일로 조용한 대체」가 그것이다.
 */
const FAILED_RECOVERY =
  '검수한 내용은 저장해 두면 그대로 남습니다. 아래 내려받기를 다시 눌러 시도할 수 있고, 그래도 실패하면 원본 파일을 다시 올려 변환해 주세요.'

/**
 * 서식 유지 패널을 그리는 원본 형식. 붙여넣기·PDF는 이 패널의 대상이 아니다(§6.5 표).
 *
 * 업로드한 평문(`txt`)도 여기 넣지 않는다 — 문단·표·이미지 같은 유지할 원본 구조가
 * 애초에 없는 형식이라 붙여넣기(`text`)와 같은 결론이다. 서버도 이 경우
 * `format_preservation.status`를 `not_applicable`로 매긴다.
 */
const PANEL_FORMATS: readonly string[] = ['docx', 'hwpx']

interface FormatPreservationPanelProps {
  sourceFormat: SourceFormat
  /**
   * 서버가 **마지막으로 준** 판정.
   *
   * `ConversionResponse` 째로 받지 않는 이유가 여기 있다. 이 판정은 **검수본의 문단
   * 수**에서 나오므로 담당자가 문단을 나누거나 합쳐 저장하면 서버의 답이 바뀐다 —
   * 조회 응답에 한 번 실려 온 값을 붙들고 있으면 화면이 「유지 가능」을 약속한 뒤
   * 다른 파일이 내려간다. 판정만 따로 받는 것이 「형식은 고정이고 이 한 값은
   * 갱신된다」를 타입으로 드러내는 방법이다.
   */
  preservation: FormatPreservation | null
}

/**
 * `원본 서식 유지` 상태 표시(DESIGN.md §6.5).
 *
 * **카드가 아니라 내려받기 버튼 바로 위의 짧은 줄이다.** §6.5 도식의 네 줄 가운데 행동은
 * 아래 sticky 줄의 버튼이 이미 맡고 있으므로 여기 남는 것은 제목·설명·상태 세 줄이다.
 * 이 제품의 중점은 변환이고 서식 유지는 곁가지라, 그 곁가지를 카드로 세워 검수 화면에서
 * 두 번째로 큰 덩어리로 만들 이유가 없다(§15의 3 — 화면에 이미 더 강한 대표 행동이 있다).
 *
 * 그리는 조건은 **DOCX·HWPX이고 판정이 `not_applicable`이 아닐 때**다. 붙여넣기는 §6.5
 * 표가 「적용 대상 아님」이라 대상이 아니고, 원본 바이트가 없어 되살릴 것이 없는 문서도
 * 같은 결론이라 아무 말도 하지 않는다 — 없는 원본을 두고 상태를 말하면 그 자체가 소음이다.
 * PDF는 [PdfExportNotice]가 따로 말한다.
 */
export function FormatPreservationPanel({
  sourceFormat,
  preservation,
}: FormatPreservationPanelProps) {
  const headingId = useId()
  const view = preservation === null ? UNKNOWN_VIEW : STATUS_VIEWS[preservation.status]
  const StatusIcon = view.icon
  const failed = preservation?.status === 'failed'

  if (!PANEL_FORMATS.includes(sourceFormat) || preservation?.status === 'not_applicable') {
    return null
  }

  return (
    <section className="mt-4 flex flex-col gap-1.5" aria-labelledby={headingId}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <h2 className="text-sm font-bold text-muted-foreground" id={headingId}>
          원본 서식 유지
        </h2>
        <span className="text-sm text-muted-foreground" aria-hidden="true">
          ·
        </span>
        <span className="text-sm font-semibold text-foreground">{sourceFormat.toUpperCase()}</span>
        <Badge tone={view.tone} withIcon={false} className="ml-auto">
          <StatusIcon className="size-3.5 shrink-0" aria-hidden="true" />
          {view.label}
        </Badge>
      </div>

      <p className="text-sm text-muted-foreground">
        원본 문서의 문단, 표, 이미지 위치에 검수한 쉬운 글을 반영합니다.
      </p>

      {/* 서버 문구를 그대로 옮긴다. 개수를 다시 세거나 문장을 합치지 않는다 — 이 줄이
          「무엇이 달라지는가」의 정본이다(§6.5). 실패가 아닐 때는 조치를 요구하지 않는
          정보이므로 보조 글자색 그대로 담담하게 둔다. */}
      {preservation !== null && preservation.details.length > 0 && (
        <ul
          className={
            failed
              ? 'flex list-disc flex-col gap-1 pl-5 text-sm text-danger'
              : 'flex list-disc flex-col gap-1 pl-5 text-sm text-muted-foreground'
          }
        >
          {preservation.details.map((detail) => (
            <li key={detail}>{detail}</li>
          ))}
        </ul>
      )}

      {failed && <p className="text-sm text-foreground">{FAILED_RECOVERY}</p>}
    </section>
  )
}

interface PdfExportNoticeProps {
  conversion: ConversionResponse
}

/**
 * PDF 원본에서 내려받기 버튼이 없는 이유(§6.5 마지막 문단).
 *
 * `export_format === null`은 「모른다」가 아니라 **「같은 형식으로 내보낼 수단이 없다」**다.
 * 그 자리를 아무 설명 없이 비워 두면 화면이 고장 난 것처럼 보이므로 사실만 두 줄로 적는다.
 *
 * **「준비 중」이라고 쓰지 않는다.** PDF 내보내기는 미구현이 아니라 **하지 않기로 정해진
 * 범위**다 — PDF는 출력용 형식이고, 편집본을 PDF로 다시 만드는 일은 이 제품의 몫이 아니다.
 * 곧 될 것처럼 적으면 오지 않을 기능을 기다리게 한다. 다른 형식으로 대신 받는 우회도
 * 제시하지 않는다(§6.5).
 */
export function PdfExportNotice({ conversion }: PdfExportNoticeProps) {
  if (conversion.source_format !== 'pdf' || conversion.export_format !== null) {
    return null
  }

  return (
    <p className="mt-4 text-sm text-muted-foreground">
      PDF는 출력용 형식이라 검수본을 같은 PDF 파일로 다시 만들지 않습니다. 그래서 이 문서에는
      내려받기가 없습니다 — 업로드와 변환, 검수와 저장은 그대로 됩니다.
    </p>
  )
}
