import { useEffect, useId, useRef, useState, useSyncExternalStore, type KeyboardEvent } from 'react'
import { Download, Save, ShieldAlert } from 'lucide-react'

import { ApiError, downloadExport, saveReview } from '../api/client'
import type { ConversionResponse, ExportFormat, SegmentMapUnit } from '../api/types'
import { cn } from '../lib/utils'
import type { DocumentSource } from '../review/sourceText'
import { setUnsavedChanges } from '../review/unsavedChanges'
import { FormatPreservationPanel, PdfExportNotice } from './FormatPreservationPanel'
import { ReviewFeedback } from './ReviewFeedback'
import { MAX_SEGMENTED_UNITS, SegmentedResultEditor } from './SegmentedResultEditor'
import { SourceTextPanel } from './SourceTextPanel'
import { TermLookupPopover } from './TermLookupPopover'
import { Badge } from './ui/Badge'
import { Button } from './ui/Button'

interface ReviewEditorProps {
  conversion: ConversionResponse
  /**
   * 왼쪽에 보여줄 원본과 그 상태.
   *
   * 종전에는 `string | null` 이었고 값이 있는 경우는 **붙여넣기 직후 한 번**뿐이었다 —
   * 파일 업로드·기록 재진입·새로고침에서는 늘 `null` 이라 비교할 대상이 없었다. 지금은
   * 서버(`GET /documents/{id}/source`)가 원문을 돌려주므로 화면은 그것을 가져오고,
   * 여기에는 **로딩·원문·실패**가 구분된 채로 들어온다(§9).
   */
  source: DocumentSource
}

/**
 * 저장·내려받기 결과 안내.
 *
 * 성공과 실패의 낭독 방식이 달라 종류를 함께 둔다. `announce`는 한 걸음 더 나눈 것으로,
 * 저장 성공처럼 위의 저장 상태 라벨이 이미 알린 사실은 눈으로만 보여준다(§11 — 같은
 * 문장을 두 번 낭독하지 않는다).
 */
interface Feedback {
  kind: 'success' | 'error'
  message: string
  announce: boolean
}

/**
 * 지금 진행 중인 작업. 어느 버튼이 도는지까지 알아야 그 버튼의 문구만 바꿀 수 있다.
 *
 * 내려받기가 둘로 나뉜 이유는 §6.5의 「저장하고 내려받기」다. 시작할 때 어느 쪽인지 정해
 * 두면 진행 문구가 **도는 도중에 바뀌지 않는다** — `dirty`로 그때그때 고르면 저장이 끝나는
 * 순간 "저장하고 내려받는 중…"이 "내려받는 중…"으로 갈아치워진다.
 */
type Pending = 'save' | 'download' | 'saveAndDownload' | null

/** 검수 패널. DOM 순서이자 탭 순서이며, §11이 요구하는 「원문 다음 결과」다. */
const PANELS = [
  { key: 'source', label: '원문' },
  { key: 'result', label: '쉬운 글' },
] as const

type PanelKey = (typeof PANELS)[number]['key']

/**
 * 이 변환을 내려받을 수 있는 형식(들).
 *
 * **대개는 목록이 아니라 서버가 정한 값 하나다**(DESIGN.md §6.5 「들어온 형식 그대로
 * 나간다」). 종전에는 `['docx','hwpx','txt']` 상수라 원본과 무관하게 버튼 셋을 그렸고,
 * 서버가 형식을 강제하기 시작한 뒤로 그중 둘은 **반드시 409로 실패한다.**
 *
 * `export_format`이 null이면 두 갈래로 갈린다(2.6.0, `export_format_choices`):
 * - 배열이 비어 있지 않으면(오늘은 PDF뿐) **그 배열 전부**를 버튼으로 그린다 — 사용자가
 *   `docx`·`hwpx` 중 하나를 직접 골라 새 문서로 받는다(§6.5 2026-09-02 재결정).
 * - 배열이 비어 있으면 빈 목록이다 — 내려받을 수단이 없는 변환에서는 내려받기 행동을
 *   제시하지 않는다(§6.5 "화면은 이 null을 보고 내려받기 행동을 제시하지 않는다"). 버튼이
 *   없는 이유는 `PdfExportNotice`가 그 자리 위에서 말한다.
 */
function downloadFormats(conversion: ConversionResponse): readonly ExportFormat[] {
  return conversion.export_format !== null
    ? [conversion.export_format]
    : conversion.export_format_choices
}

/** 원문과 결과를 나란히 담을 수 있는 최소 너비(DESIGN.md §6.4·§10). */
const SPLIT_VIEW_QUERY = '(min-width: 1024px)'

function splitViewMedia(): MediaQueryList | null {
  // matchMedia가 없는 환경(jsdom)도 있다. 없으면 2열로 본다 — 2열은 두 패널을 모두
  // 펼쳐 두는 쪽이라 어떤 것도 감추지 않는다.
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(SPLIT_VIEW_QUERY)
    : null
}

function subscribeToSplitView(onChange: () => void): () => void {
  const media = splitViewMedia()
  media?.addEventListener('change', onChange)
  return () => media?.removeEventListener('change', onChange)
}

function getSplitView(): boolean {
  return splitViewMedia()?.matches ?? true
}

/**
 * 지금 화면이 원문과 결과를 나란히 담을 만큼 넓은지.
 *
 * CSS로 감추지 않고 JS로 판정하는 이유: 탭은 모양이 아니라 의미다. `role="tab"`을 늘
 * 그려 두고 넓은 화면에서 CSS로만 감추면, 두 패널이 나란히 보이는 화면에서도 낭독기는
 * "탭 2개 중 1번째"라고 읽는다. 실제로 하나만 보이는 폭에서만 탭 의미를 만든다.
 */
function useSplitView(): boolean {
  return useSyncExternalStore(subscribeToSplitView, getSplitView, () => true)
}

/** blob을 사용자의 내려받기 폴더로 보낸다. */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  // 문서에 붙여야 클릭이 먹는 브라우저가 있다(파이어폭스). 끝나면 바로 걷어낸다.
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 해제하지 않으면 blob이 탭이 닫힐 때까지 메모리에 남는다.
  URL.revokeObjectURL(url)
}

/**
 * 분할 화면 검수 에디터.
 *
 * 왼쪽은 원본, 오른쪽은 고칠 수 있는 결과다. 초기값은 `edited_text ?? easy_text` —
 * AI 초안은 수정률 KPI의 기준선이라 서버에 그대로 남고, 담당자가 이어서 고칠 대상은
 * 마지막으로 저장한 수정본이다.
 *
 * 화면 맨 위의 "AI가 만든 초안" 배너는 지우지 않는다(master-plan 3.3 HITL).
 */
export function ReviewEditor({ conversion, source }: ReviewEditorProps) {
  const editorId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const tabRefs = useRef<Partial<Record<PanelKey, HTMLButtonElement | null>>>({})
  /** 결과 패널 상자 — 사전 팝업(TermLookupPopover)이 선택 이벤트를 거는 대상이다. */
  const resultPanelRef = useRef<HTMLDivElement>(null)
  const initialText = conversion.edited_text ?? conversion.easy_text ?? ''

  const [draft, setDraft] = useState(initialText)
  /** 마지막으로 서버에 저장된 글. 이것과 draft가 다르면 저장하지 않은 변경이다. */
  const [savedText, setSavedText] = useState(initialText)
  const [reviewedAt, setReviewedAt] = useState(conversion.reviewed_at)
  /**
   * 이 변환에 의견을 보낸 시각. 아래 피드백 폼이 보내는 즉시 여기로 올라온다.
   *
   * 상태를 위에 두는 이유: 「의견을 보냈다」는 사실을 말해야 하는 곳은 이 화면의 상태
   * 패널이고, 그것은 폼보다 위에 있다. 폼 안에만 남겨 두면 사용자는 토스트가 사라진 뒤
   * 화면 맨 위에서 여전히 「아직 저장한 검수 내용이 없습니다」만 읽고 제출이 실패한 줄
   * 안다 — 실제로 서버에는 저장돼 있는데도.
   */
  const [feedbackSubmittedAt, setFeedbackSubmittedAt] = useState(conversion.feedback_submitted_at)
  /**
   * 「의견을 보냈다」로 볼 수 있는가.
   *
   * 계약은 `feedback_submitted_at` 키가 늘 있다고 정하지만, 그것은 서버의 약속이지 이
   * 컴포넌트가 받는 값의 보장이 아니다. 필드를 아직 안 싣는 서버·배포 시차로 남은 옛
   * 번들·목을 덜 고친 테스트에서는 `undefined`가 들어오고, `!== null` 비교는 그것을
   * **보낸 것으로** 읽어 `new Date(undefined)` — `Invalid Date` — 를 배지에 찍는다.
   * 값이 실제로 시각 문자열일 때만 참으로 둔다.
   */
  const hasFeedback = typeof feedbackSubmittedAt === 'string'
  /**
   * 서버가 마지막으로 준 서식 유지 판정.
   *
   * 조회 응답에서 한 번 받고 마는 값이 **아니다.** 이 판정은 검수본의 문단 수와 원본
   * 구조 단위의 짝에서 나오므로, 담당자가 검수하며 문단을 나누거나 합쳐 저장하면 서버의
   * 답이 바뀐다. 저장 응답이 그 새 판정을 싣고 오고(`GET`과 같은 스키마다) 화면은 그것을
   * 그대로 옮긴다 — 여기서 값을 붙들고 있으면 패널이 「유지 가능」이라고 말한 뒤 실제로는
   * 그렇지 않은 파일이 내려간다(§6.5 «상태는 낙관적으로 추측하지 않는다»).
   *
   * 판정을 화면에서 다시 세지 않는 것이 요점이다 — 규칙은 서버 한 곳에만 있다.
   */
  const [preservation, setPreservation] = useState(conversion.format_preservation)
  /**
   * 문단 단위 대응표(계약 2.12.0, §6.4 S3). 서버가 매 조회·저장마다 다시 유도해 주는
   * 값이지만, 문단을 나누거나 합치는 조작은 다음 저장까지 서버가 모르므로 여기서
   * 국소적으로 갱신한다(계약 `segment_map` 설명 — 서버가 강제하지 않는 클라이언트
   * 재계산). 저장이 끝나면 서버가 다시 잰 값으로 덮어써 낡은 추정을 남기지 않는다.
   */
  const [unitMap, setUnitMap] = useState<SegmentMapUnit[]>(conversion.segment_map?.units ?? [])
  /** 결과 단위 hover·focus가 밝힌, 지금 하이라이트해야 할 원본 단위 색인들. */
  const [highlightedSourceIndexes, setHighlightedSourceIndexes] = useState<number[]>([])
  /** 원본 단위 hover·focus 중인 색인. 결과 쪽 단위 하이라이트를 계산하는 재료다. */
  const [hoveredSourceIndex, setHoveredSourceIndex] = useState<number | null>(null)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [pending, setPending] = useState<Pending>(null)
  /**
   * 지금 내려받는 중인 형식.
   *
   * PDF 원본은 버튼이 둘일 수 있다(`export_format_choices`) — `pending`만으로는 어느
   * 버튼을 눌렀는지 구분되지 않아 두 버튼이 동시에 "내려받는 중…"이라고 말하게 된다.
   * 이 값은 그 버튼 하나만 도는 것처럼 보이게 한다. 나머지 버튼은 `busy`로 여전히
   * 잠기지만 문구는 그대로 둔다 — 누른 적 없는 버튼이 진행 중이라고 말하지 않는다.
   */
  const [pendingFormat, setPendingFormat] = useState<ExportFormat | null>(null)
  const [activePanel, setActivePanel] = useState<PanelKey>('source')
  /** 저장·내려받기를 누른 버튼. 그 작업이 끝나면 초점을 여기로 돌린다. */
  const refocusRef = useRef<HTMLButtonElement | null>(null)
  /**
   * 지금 초점이 들어 있는 패널. 어느 쪽에도 없으면 `null`이다.
   *
   * 패널 상자에 건 focus·blur가 채운다 — 초점 사건은 거품처럼 올라오므로 상자 하나가
   * 그 안의 입력칸·버튼을 모두 대신한다. ref가 아니라 상태로 두는 이유: 아래 탭 전이
   * 판정이 **렌더 중에** 이 값을 읽어야 하고, 렌더 중 ref 읽기는 금지돼 있다.
   */
  const [focusedPanel, setFocusedPanel] = useState<PanelKey | null>(null)
  /**
   * 사용자가 탭을 직접 고른 적이 있는가.
   *
   * 골랐다면 그 선택이 이후의 모든 자동 판정을 이긴다 — 아래 탭 전이 판정이 그것을 다시
   * 덮어쓰면, 원문을 보려고 탭을 누른 사람이 창 크기를 바꿀 때마다 결과로 튕긴다.
   */
  const [panelPickedByUser, setPanelPickedByUser] = useState(false)
  /**
   * 언제나 최신 `draft`를 가리키는 ref(§MEDIUM 리뷰).
   *
   * `persistDraft`는 저장 요청을 보낸 시점의 `draft`(클로저 값)를 쥔 채로 응답을
   * 기다린다 — 그 사이 사용자가 이어서 고치면 `draft` state는 바뀌지만 그 클로저는
   * 여전히 옛 값을 본다. 응답이 온 뒤 "보낸 값과 지금 값이 같은가"를 물으려면 클로저가
   * 아니라 **항상 최신인** 값이 필요하고, 그것이 이 ref다. 렌더 중에는 ref를 쓰지 않는다
   * (react-hooks/refs) — effect에서 커밋 직후에 맞춘다.
   */
  const draftRef = useRef(draft)
  useEffect(() => {
    draftRef.current = draft
  }, [draft])

  const dirty = draft !== savedText
  const busy = pending !== null
  /** 내려받기 버튼이 도는 중인지. 저장을 먼저 하는 경로도 같은 버튼이 돈다. */
  const downloading = pending === 'download' || pending === 'saveAndDownload'
  const splitView = useSplitView()

  /**
   * 결과 패널을 단위 목록(`SegmentedResultEditor`)으로 그릴지.
   *
   * 대응표가 없으면(`segment_map: null`) 애초에 비교할 지도가 없으니 옛 단일 textarea
   * 그대로다. 단위 수가 상한(`MAX_SEGMENTED_UNITS`)을 넘으면 지도가 있어도 내려앉는다
   * — 계획 §6 S3 "단위 수가 200을 넘으면 지금의 단일 textarea로 내려앉는다".
   */
  const unitCount = draft.split('\n').length
  const useSegmentedEditor = conversion.segment_map !== null && unitCount <= MAX_SEGMENTED_UNITS
  const showFallbackBanner = conversion.segment_map !== null && unitCount > MAX_SEGMENTED_UNITS

  /**
   * 좁은 화면에서 탭으로 바꿀지.
   *
   * 원문이 아직 없으면(불러오는 중이거나 못 불러왔으면) 탭을 만들지 않는다. 고를 수
   * 있는 것이 하나뿐인 탭 줄은 조작할 이유가 없는 장치이고, 「불러오는 중」이나 「불러오지
   * 못함」을 탭 뒤에 숨기면 그 사실 자체가 사용자에게 닿지 않는다(§9 — 로딩·실패·원문은
   * 서로 다른 상태다). 그래서 그 경로에서는 설명 카드와 편집기를 위아래로 그대로 쌓는다.
   */
  const showTabs = !splitView && source.state.status === 'ready'
  const statusId = `${editorId}-save-status`
  const resultHeadingId = `${editorId}-result-heading`

  /**
   * 탭이 **처음 생기는 순간** 어느 패널을 펼쳐 둘지 정한다.
   *
   * 탭이 없는 동안에는 두 패널이 위아래로 모두 보이므로 사용자는 결과 편집기에 바로
   * 타이핑할 수 있다. 그런데 원문이 늦게 도착해 `showTabs`가 참으로 뒤집히면 그 순간
   * 초기값 `'source'`가 **편집 중이던 결과 패널을 통째로 숨긴다** — 글도 초점도 눈앞에서
   * 사라진다. 네트워크가 느릴수록 더 오래 타이핑하다 당한다.
   *
   * 그래서 전이 시점에 「사용자가 지금 어디에 있는가」를 묻는다. 순서가 곧 규칙이다.
   *
   * 1. **초점이 어느 패널 안에 있으면 그 패널이 이긴다.** 지금 손이 가 있는 곳을 숨기는
   *    것이 이 버그의 정체이므로, 과거에 고른 탭보다 강한 신호다 — 탭을 골라 둔 뒤
   *    화면을 넓혀 결과를 고치다가 다시 좁히는 경로가 그 예다.
   * 2. 초점이 어디에도 없고 사용자가 탭을 고른 적이 있으면 **그 선택을 그대로 둔다.**
   *    창 크기 조절 한 번에 남의 선택을 되돌리지 않는다.
   * 3. 둘 다 아니면 고쳐 둔 내용(`dirty`)이 있는 쪽을 편들고, 그것도 없으면 §11의 읽기
   *    순서대로 원문이 먼저다.
   *
   * **효과가 아니라 렌더 중에** 정하는 것이 요점이다. 효과는 DOM이 이미 갱신된 뒤에
   * 돌아서, 그때는 `hidden`이 붙으며 초점이 `<body>`로 떨어진 다음이다. 렌더 중 상태
   * 조정은 커밋 전에 다시 렌더되므로 결과 패널에 `hidden`이 한 번도 붙지 않는다
   * (React가 문서화한 «렌더 중 상태 조정» 패턴이다).
   *
   * 그리고 **전이할 때만** 판정한다 — 매 렌더 판정하면 사용자가 방금 누른 탭을 곧바로
   * 덮어쓴다.
   */
  const [tabsWereShown, setTabsWereShown] = useState(showTabs)
  if (showTabs !== tabsWereShown) {
    setTabsWereShown(showTabs)
    if (showTabs) {
      if (focusedPanel !== null) {
        setActivePanel(focusedPanel)
      } else if (!panelPickedByUser) {
        setActivePanel(dirty ? 'result' : 'source')
      }
    }
  }

  // 폴링이 끝나 에디터가 나타나는 순간 초점이 화면 맨 위에 그대로 있으면, 낭독기
  // 사용자는 결과가 나왔다는 것을 알 수 없다 — 새 화면의 제목으로 초점을 옮긴다.
  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  // 탭을 닫거나 새로고침하는 경로. 브라우저는 우리 문구 대신 자기 확인창을 띄우므로
  // preventDefault만 하면 된다(문구 지정은 최신 브라우저에서 무시된다).
  useEffect(() => {
    setUnsavedChanges(dirty)
    if (!dirty) {
      return
    }
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  // 화면을 떠날 때 경고 상태를 반드시 끈다 — 켜진 채로 두면 다음 화면에서 이유 없이
  // "저장하지 않은 수정이 있다"고 묻는다.
  useEffect(() => () => setUnsavedChanges(false), [])

  /**
   * 저장·내려받기가 끝나면 방금 누른 버튼으로 초점을 돌린다.
   *
   * 이 버튼들은 진행 중에 `disabled` 가 된다. 브라우저는 초점을 가진 요소가 잠기는
   * 순간 초점을 `<body>` 로 떨어뜨리므로, 키보드 사용자는 저장을 누른 대가로 지금까지
   * 온 탭 경로를 통째로 잃고 문서 맨 앞에서 다시 밟아야 한다(§14 «키보드만으로 검수
   * 저장과 내려받기까지 이동할 수 있다»).
   *
   * `finally` 가 아니라 effect 에서 돌리는 이유: `finally` 시점에는 아직 리렌더 전이라
   * 버튼이 잠긴 상태이고, 잠긴 버튼은 초점을 받지 못한다. `pending` 이 풀린 뒤 DOM 이
   * 갱신된 이 자리가 초점을 받을 수 있는 첫 순간이다.
   *
   * 초점이 `<body>` 에 있을 때만 돌린다 — 기다리는 동안 사용자가 다른 곳으로 옮겨 갔다면
   * 그 초점을 빼앗지 않는다.
   */
  useEffect(() => {
    if (pending !== null) {
      return
    }
    const trigger = refocusRef.current
    refocusRef.current = null
    if (trigger !== null && document.activeElement === document.body) {
      trigger.focus()
    }
  }, [pending])

  /**
   * 검수본을 서버에 저장하고 화면 상태를 맞춘다.
   *
   * 저장 버튼과 `저장하고 내려받기`가 같은 함수를 지난다 — 두 경로가 저장을 서로 다르게
   * 하면 "저장했는데 파일에는 안 담겼다"는 갈래가 생긴다.
   */
  async function persistDraft(): Promise<void> {
    const sentDraft = draft
    const saved = await saveReview(conversion.id, sentDraft)
    // 기다리는 동안 사용자가 이어서 고쳤다면(§MEDIUM 리뷰) 이 응답은 그때 보낸
    // `sentDraft`에 대한 것일 뿐, 지금 화면의 최신 draft에 대한 것이 아니다. 그대로
    // 덮어쓰면 방금 고친 내용이 사라지고, `unitMap`도 그 낡은 텍스트의 구조로 다시
    // 짜여 지금 draft의 단위 수와 어긋난다(CRITICAL 리뷰가 지적한 것과 같은 종류의
    // 불변식 붕괴). 이 응답이 낡았으면 아무 것도 덮어쓰지 않고 물러난다 — 저장 안 됨
    // 상태는 `savedText`가 그대로 남아 자연히 유지된다.
    if (draftRef.current !== sentDraft) {
      return
    }
    // 서버가 다듬은 결과(제어문자 제거 등)를 그대로 화면에 반영한다 — 우리가 보낸
    // 글을 저장본으로 삼으면 저장 직후에도 "수정됨" 표시가 남는 경우가 생긴다.
    const stored = saved.edited_text ?? sentDraft
    setDraft(stored)
    setSavedText(stored)
    setReviewedAt(saved.reviewed_at)
    // 방금 저장한 글로 서버가 다시 잰 판정이다. `??`로 옛 값을 붙들지 않는다 — 계약에서
    // 이 키는 늘 있고 `null`은 「아직 판정하지 않았다」라는 서버의 답이라, 그것을 지난
    // 조회의 판정으로 메우면 화면이 서버가 하지 않은 말을 하게 된다.
    setPreservation(saved.format_preservation)
    // 대응표도 같은 이유로 서버 응답이 이길 때마다 갱신한다 — 분할·병합으로 만든
    // 로컬 추정은 서버가 다시 잰 값이 오는 순간 버려진다.
    setUnitMap(saved.segment_map?.units ?? [])
  }

  /** 서버가 준 사유를 문장 뒤에 붙인다. ApiError가 아니면 붙일 사유가 없다. */
  function reasonOf(caught: unknown): string {
    return caught instanceof ApiError ? ` 사유: ${caught.message}.` : ''
  }

  async function handleSave(): Promise<void> {
    setPending('save')
    setFeedback(null)
    try {
      await persistDraft()
      // 저장됐다는 사실은 위 상태 라벨이 `저장됨 · 시각`으로 알린다. 여기 문구는 방금
      // 누른 버튼 옆에 결과를 남기는 보조 수단이라 낭독하지 않는다(§9 성공 토스트는
      // 보조 수단, §11 중복 낭독 금지).
      setFeedback({ kind: 'success', message: '검수 내용을 저장했습니다.', announce: false })
    } catch (caught) {
      setFeedback({
        kind: 'error',
        message:
          caught instanceof ApiError
            ? caught.message
            : '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        announce: true,
      })
    } finally {
      setPending(null)
    }
  }

  /**
   * 저장하지 않은 수정이 있으면 **먼저 저장하고** 내려받는다(§6.5 「한 번의 명확한 행동」).
   *
   * 내려받는 파일에는 서버에 저장된 글만 담긴다. 그래서 "저장한 내용만 담깁니다" 같은
   * 안내로 사용자에게 순서를 떠넘기지 않고 화면이 두 걸음을 한 번에 밟는다.
   *
   * 실패했을 때 **어느 걸음에서 멈췄는지**를 문구가 말한다(§9 — 일어난 일, 보존된 데이터,
   * 다시 할 수 있는 일). 저장부터 실패한 경우와 저장은 됐는데 내려받기가 실패한 경우는
   * 사용자가 다음에 할 일이 다르다: 앞은 다시 저장부터, 뒤는 내려받기만 다시다.
   */
  async function handleDownload(format: ExportFormat): Promise<void> {
    const name = format.toUpperCase()
    const needsSave = dirty
    let saved = false
    setPending(needsSave ? 'saveAndDownload' : 'download')
    setPendingFormat(format)
    setFeedback(null)
    try {
      if (needsSave) {
        await persistDraft()
        saved = true
      }
      const downloaded = await downloadExport(conversion.id, format)
      saveBlob(downloaded.blob, downloaded.filename ?? `쉬운 글.${format}`)
      // 내려받기 결과는 화면 어디에도 남지 않는 사실이라 이쪽은 낭독한다.
      setFeedback({
        kind: 'success',
        message: saved
          ? `검수 내용을 저장하고 ${name} 파일을 내려받았습니다.`
          : `${name} 파일을 내려받았습니다.`,
        announce: true,
      })
    } catch (caught) {
      // 자리표시자가 빠진 초안은 내려받을 수 없다(409) — 그 사유도 백엔드 문구로 온다.
      const message =
        needsSave && !saved
          ? `검수 내용을 저장하지 못해 ${name} 파일을 내려받지 않았습니다.${reasonOf(caught)} 고친 내용은 화면에 그대로 있습니다. 다시 시도해 주세요.`
          : saved
            ? `검수 내용은 저장했습니다. ${name} 파일만 내려받지 못했습니다.${reasonOf(caught)} 저장된 내용은 그대로이니 내려받기를 다시 눌러 주세요.`
            : `${name} 파일을 내려받지 못했습니다.${reasonOf(caught)} 저장된 내용은 그대로입니다. 다시 시도해 주세요.`
      setFeedback({ kind: 'error', message, announce: true })
    } finally {
      setPending(null)
      setPendingFormat(null)
    }
  }

  /** 탭 사이 이동. 선택은 초점을 따라간다(WAI-ARIA 탭 패턴의 기본형). */
  function handlePanelKeyDown(event: KeyboardEvent<HTMLButtonElement>): void {
    const keys: readonly PanelKey[] = PANELS.map((panel) => panel.key)
    const current = keys.indexOf(activePanel)
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : null
    const next =
      step !== null
        ? keys[(current + step + keys.length) % keys.length]
        : event.key === 'Home'
          ? keys[0]
          : event.key === 'End'
            ? keys[keys.length - 1]
            : undefined
    if (next === undefined) {
      return
    }
    event.preventDefault()
    setPanelPickedByUser(true)
    setActivePanel(next)
    tabRefs.current[next]?.focus()
  }

  /**
   * 탭일 때만 패널 의미를 붙인다. 2열로 함께 보일 때는 탭 패널이 아니라 그냥 두 카드다.
   */
  function panelProps(key: PanelKey) {
    return showTabs
      ? ({
          role: 'tabpanel',
          id: `${editorId}-${key}-panel`,
          'aria-labelledby': `${editorId}-${key}-tab`,
          hidden: activePanel !== key,
        } as const)
      : {}
  }

  const status = dirty
    ? {
        tone: 'warning' as const,
        label: '저장 안 됨',
        // 내려받기가 있는 화면에서는 순서를 사용자에게 떠넘기지 않는다 — 그 버튼이
        // 저장까지 한 번에 한다(§6.5). 내려받을 수단이 없는 원본(PDF)에서는 그 약속을
        // 하지 않는다.
        detail:
          downloadFormats(conversion).length > 0
            ? '내려받기를 누르면 저장한 뒤 파일을 만듭니다.'
            : '아직 서버에 저장하지 않은 수정이 있습니다.',
      }
    : reviewedAt === null
      ? {
          tone: 'info' as const,
          label: '저장 전',
          // 의견을 보낸 뒤에는 「아직 …이 없습니다」가 "내 제출이 실패했나"로 읽힌다.
          // 저장하지 않았다는 사실은 그대로 두되, 아직 할 일이 남았다는 뜻으로 들리지
          // 않게 완료형으로 적는다. 무엇을 보냈는지는 옆의 「의견 보냄」이 말한다.
          // 값의 유무를 `=== null`이 아니라 타입으로 묻는다 — 필드를 아직 안 싣는 서버나
          // 옛 번들에서는 `undefined`가 오고, 그때 `=== null`은 거짓이라 의견을 낸 적
          // 없는 화면이 「보냈다」 쪽 문구를 읽는다. 모르면 「아직」이 안전한 오답이다.
          detail: hasFeedback
            ? '고쳐서 저장한 내용은 없습니다. 결과는 AI 초안 그대로입니다.'
            : '아직 저장한 검수 내용이 없습니다. AI 초안 그대로입니다.',
        }
      : {
          tone: 'success' as const,
          label: `저장됨 · ${new Date(reviewedAt).toLocaleString('ko-KR')}`,
          detail: null,
        }

  return (
    <section className="flex flex-col gap-5" aria-labelledby="review-heading">
      {/* §6.4 상단 줄: 왼쪽은 HITL 고지, 오른쪽은 저장 상태다.
          HITL 고지는 이 화면에서 가장 먼저 읽혀야 하는 문장이라 DOM에서도 앞에 둔다. */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <p
          className="flex items-center gap-2 rounded-[10px] border border-warning/25 bg-warning-surface px-4 py-3 font-semibold text-warning"
          role="note"
        >
          <ShieldAlert className="size-5 shrink-0" aria-hidden="true" />
          AI가 만든 초안입니다 — 반드시 검토 후 사용하세요.
        </p>

        {/* 이 화면에서 "저장했는가"를 말하는 곳은 여기 하나다. 저장 여부는 토스트로
            흘려보내지 않고 화면에 남긴다(§9). 색만으로 구분하지 않도록 배지에 문구와
            아이콘을 함께 둔다(§8.1). */}
        <div className="flex shrink-0 flex-col items-start gap-2 sm:items-end">
          <div className="flex flex-col items-start gap-1 sm:items-end" id={statusId} role="status">
            <Badge tone={status.tone}>{status.label}</Badge>
            {status.detail !== null && (
              <span className="text-sm text-muted-foreground sm:text-right">{status.detail}</span>
            )}
          </div>

          {/* 의견을 보냈다는 사실은 저장 상태와 **다른 사실**이라 배지를 따로 둔다 —
              「저장 전」과 「의견 보냄」이 동시에 참일 수 있고, 하나로 뭉치면 어느 쪽이
              끝난 일인지 화면에서 사라진다. 색만으로 구분하지 않도록 배지에 시각까지
              문구로 적는다(§8.1·§9).

              위 `role="status"` 바깥에 두는 것이 중요하다. 여기에 넣으면 제출 성공을
              폼의 안내와 이 배지가 잇달아 두 번 낭독한다(§11 중복 낭독 금지) —
              「의견을 보냈습니다」는 폼이 이미 말했고, 이 배지는 그 뒤에도 화면에
              남아 있는 기록이 그 몫이다. */}
          {hasFeedback && (
            <div className="flex flex-col items-start gap-1 sm:items-end">
              <Badge tone="success">
                의견 보냄 · {new Date(feedbackSubmittedAt).toLocaleString('ko-KR')}
              </Badge>
              {/* 의견의 내용은 서버가 돌려주지 않는다. 다시 볼 수 있는 척하지 않고
                  없다고 적는다(§15 — 없는 기능을 있는 것처럼 보이게 하지 않는다). */}
              <span className="text-sm text-muted-foreground sm:text-right">
                검수 내용 저장과 따로 기록되며, 적은 내용은 이 화면에 다시 표시되지 않습니다.
              </span>
            </div>
          )}
        </div>
      </div>

      <header className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
        <div>
          <Badge tone="success" className="mb-2">
            변환 완료
          </Badge>
          {/*
            이 화면의 h1이다(§11 «제목 순서»). 검수 화면은 `PageHeader`를 쓰지 않는다 —
            그 컴포넌트는 맥락 라벨과 오른쪽 대표 행동을 전제하는데 여기서는 위의 HITL
            고지와 저장 상태가 그 자리를 쓴다. 그래도 **본문의 첫 제목은 h1이어야 한다**:
            h2로 시작하면 낭독기 목차에 뿌리가 없어 "지금 어느 화면인가"를 제목으로
            물을 수 없다(머리말의 로고는 제목이 아니다).

            글자 크기는 클래스가 정하므로 태그를 바꿔도 보이는 모양은 그대로다.
          */}
          <h1
            className="text-2xl font-extrabold tracking-tight"
            id="review-heading"
            ref={headingRef}
            tabIndex={-1}
          >
            쉬운 글 검수
          </h1>
          <p className="mt-1 text-[15px] text-muted-foreground">
            원문과 AI 초안을 비교하고, 필요한 내용을 직접 고쳐 주세요.
          </p>
        </div>
      </header>

      {/* 편집 영역과 그 행동을 한 묶음으로 둔다. 아래 행동 줄이 붙어 있는 구간이 이
          묶음 안에서 끝나야 피드백 폼과 대응표를 가리지 않는다(§10). */}
      <div className="flex flex-col">
        {showTabs && (
          <div
            className="mb-3 flex gap-1 rounded-[12px] border border-border bg-muted p-1"
            role="tablist"
            aria-label="검수 화면"
          >
            {PANELS.map((panel) => (
              <button
                key={panel.key}
                ref={(node) => {
                  tabRefs.current[panel.key] = node
                }}
                type="button"
                role="tab"
                id={`${editorId}-${panel.key}-tab`}
                aria-selected={activePanel === panel.key}
                aria-controls={`${editorId}-${panel.key}-panel`}
                tabIndex={activePanel === panel.key ? 0 : -1}
                className={cn(
                  'flex h-11 flex-1 items-center justify-center rounded-[10px] px-3 text-[15px] font-semibold transition-colors',
                  activePanel === panel.key
                    ? 'bg-card text-primary shadow-sm'
                    : 'text-muted-foreground hover:text-foreground',
                )}
                onClick={() => {
                  setPanelPickedByUser(true)
                  setActivePanel(panel.key)
                }}
                onKeyDown={handlePanelKeyDown}
              >
                {panel.label}
              </button>
            ))}
          </div>
        )}

        {/* §11: 읽기 순서는 넓은 화면에서도 탭에서도 원문 다음 결과다. */}
        <div className="grid gap-4 lg:grid-cols-2">
          {/* 초점 추적은 패널 상자에 건다 — 초점 사건은 거품처럼 올라오므로 상자 하나가
              그 안의 입력칸과 버튼을 모두 대신한다(위 `focusedPanel`). */}
          <div
            className="rounded-[12px] border border-border bg-card p-5"
            onFocus={() => setFocusedPanel('source')}
            onBlur={() => setFocusedPanel(null)}
            {...panelProps('source')}
          >
            {/* 로딩·실패·원문을 가르는 것은 이 패널이다(§9). 빈 textarea를 만들면 "아직
                안 왔음"과 "못 가져왔음"이 같은 모양이 되므로 상태마다 다르게 말한다. */}
            <SourceTextPanel
              source={source}
              textareaId={`${editorId}-source`}
              failureNote="가린 개인정보는 아래 대응표에서 확인할 수 있습니다."
              units={
                useSegmentedEditor && source.state.status === 'ready'
                  ? source.state.text.split('\n')
                  : undefined
              }
              highlightedIndexes={
                useSegmentedEditor ? new Set(highlightedSourceIndexes) : undefined
              }
              onHoverUnit={useSegmentedEditor ? setHoveredSourceIndex : undefined}
            />
          </div>

          {/* 포인트색 경계로 "여기가 고치는 쪽"임을 원문 패널과 구분한다(§6.4). */}
          <div
            ref={resultPanelRef}
            className="rounded-[12px] border-2 border-primary/40 bg-card p-5"
            onFocus={() => setFocusedPanel('result')}
            onBlur={() => setFocusedPanel(null)}
            {...panelProps('result')}
          >
            <div className="mb-2 flex items-center justify-between gap-2">
              <h2 className="text-sm font-bold text-primary" id={resultHeadingId}>
                {/* 단위 목록 모드에는 단일 입력이 없어 `label htmlFor`로 묶을 대상이
                    없다 — `SegmentedResultEditor`의 `role="group"`이 이 id를
                    `aria-labelledby`로 대신 참조한다. */}
                {useSegmentedEditor ? (
                  '쉬운 글 결과 (고칠 수 있습니다)'
                ) : (
                  <label htmlFor={editorId}>쉬운 글 결과 (고칠 수 있습니다)</label>
                )}
              </h2>
              {/* 눈으로 두 패널을 가르는 표식이다. 같은 사실을 위 라벨이 이미 말하므로
                  낭독기에서는 감춘다 — 한 입력에 두 번 붙는 설명이 된다. */}
              <Badge tone="primary" className="shrink-0" aria-hidden="true">
                편집 가능
              </Badge>
            </div>

            {/* 문단이 상한을 넘어 단위 목록 대신 단일 글상자로 내려앉은 이유를 그
                자리에서 설명한다(계획 §6 S3) — 재변환은 이 슬라이스 범위 밖이다(S4). */}
            {showFallbackBanner && (
              <p className="field-hint mb-2">
                문단이 {MAX_SEGMENTED_UNITS}개를 넘어 문단별 편집 대신 하나의 글상자로 보여드립니다.
              </p>
            )}

            {useSegmentedEditor ? (
              <SegmentedResultEditor
                headingId={resultHeadingId}
                value={draft}
                onChange={setDraft}
                unitMap={unitMap}
                onUnitMapChange={setUnitMap}
                hoveredSourceIndex={hoveredSourceIndex}
                onHoverUnit={setHighlightedSourceIndexes}
                disabled={busy}
              />
            ) : (
              <textarea
                id={editorId}
                className="review-textarea text-[17px] leading-[1.75]"
                value={draft}
                rows={20}
                onChange={(event) => setDraft(event.target.value)}
                disabled={busy}
              />
            )}
          </div>
        </div>

        {/* 선택 기반 사전 팝업(P0-5 조각 5, 계획 §3.5) — 결과 패널 안 textarea에서
            글자를 선택하면(더블클릭 포함) 250ms 뒤 후보를 띄운다. 위치 자체는 포털이라
            이 자리에 둘 필요는 없지만, 결과 편집 영역과 논리적으로 묶어 둔다. */}
        <TermLookupPopover
          containerRef={resultPanelRef}
          value={draft}
          onApply={setDraft}
          disabled={busy}
        />

        {/* 내려받기를 막는 이유는 내려받기 버튼 가까이에 둔다(§6.4). */}
        {conversion.missing_placeholders.length > 0 && (
          <p className="review-warning mt-4 mb-0">
            <strong>주의:</strong> 가린 개인정보 자리표시자{' '}
            {conversion.missing_placeholders.join(', ')}가 결과에서 빠졌습니다. 해당 내용이 필요하면
            아래 표를 보고 직접 넣어 주세요. 자리표시자가 빠진 채로는 파일을 내려받을 수 없습니다.
          </p>
        )}

        {/* §6.5 — 내려받기 버튼을 누르기 직전에 원본 서식이 어떻게 되는지 읽게 한다.
            DOCX·HWPX가 아니면 패널은 스스로 아무것도 그리지 않고, PDF는 내려받기 버튼이
            없는 이유를 대신 말한다. */}
        <FormatPreservationPanel
          sourceFormat={conversion.source_format}
          preservation={preservation}
        />
        <PdfExportNotice conversion={conversion} />

        {/* 저장·내려받기 결과는 방금 누른 버튼 바로 위에 남긴다. 실패는 즉시(alert)
            알리고, 성공은 하던 일을 끊지 않게(status) 알리되 저장 성공은 위 상태
            라벨이 이미 말했으므로 낭독하지 않는다. */}
        {feedback !== null && (
          <p
            className={cn('mt-4', feedback.kind === 'error' ? 'form-error' : 'form-success')}
            role={feedback.kind === 'error' ? 'alert' : feedback.announce ? 'status' : undefined}
          >
            {feedback.message}
          </p>
        )}

        {/* 긴 본문을 스크롤하는 동안에도 저장에 닿아야 한다(§6.4). 화면 하단 고정(fixed)이
            아니라 이 편집 묶음 안에서만 붙는 sticky다 — 묶음을 지나가면 함께 흘러가므로
            본문 마지막 요소(피드백·대응표)를 가리지 않는다(§10). 아래 여백은 홈
            인디케이터가 있는 기기에서 버튼이 잘리지 않게 안전 영역만큼 더 준다. */}
        <div className="sticky bottom-0 z-10 mt-4 flex flex-wrap items-center gap-2 border-t border-border bg-background/95 pt-3 backdrop-blur-sm [padding-bottom:max(0.75rem,env(safe-area-inset-bottom))]">
          <Button
            type="button"
            className={cn('h-11 w-full sm:w-auto', dirty && 'ring-2 ring-ring/40')}
            variant={dirty ? 'primary' : 'secondary'}
            aria-describedby={statusId}
            onClick={(event) => {
              refocusRef.current = event.currentTarget
              void handleSave()
            }}
            disabled={busy}
            loading={pending === 'save'}
          >
            {pending !== 'save' && <Save className="size-[18px]" aria-hidden="true" />}
            {pending === 'save' ? '저장 중…' : '검수 내용 저장'}
          </Button>
          {downloadFormats(conversion).map((format) => {
            // 이 버튼을 눌러서 도는 중인지 — PDF 원본은 버튼이 둘일 수 있어(위
            // `pendingFormat`) `pending`만으로는 어느 버튼인지 구분되지 않는다.
            const thisDownloading = downloading && pendingFormat === format
            return (
              <Button
                key={format}
                className="h-11 grow sm:grow-0"
                variant="outline"
                type="button"
                onClick={(event) => {
                  refocusRef.current = event.currentTarget
                  void handleDownload(format)
                }}
                disabled={busy}
                loading={thisDownloading}
              >
                {!thisDownloading && <Download className="size-[18px]" aria-hidden="true" />}
                {/* 저장하지 않은 수정이 있으면 두 걸음을 한 버튼 이름으로 말한다(§6.5).
                    형식 이름을 버튼에 넣어 무엇이 나오는지 누르기 전에 알린다 — 누른 뒤
                    형식을 고르게 하는 모달은 두지 않는다. */}
                {pending === 'saveAndDownload' && thisDownloading
                  ? '저장하고 내려받는 중…'
                  : pending === 'download' && thisDownloading
                    ? '내려받는 중…'
                    : `${dirty ? '저장하고 ' : ''}${format.toUpperCase()}로 내려받기`}
              </Button>
            )
          })}
        </div>
      </div>

      {/* 결과를 다 보고 난 자리에 둔다 — 검수 전에 묻는 만족도는 결과가 아니라 기대치를
          재게 된다. 이 화면은 status가 done일 때만 그려지므로(ConversionPage) 서버가
          409로 막는 조건과 화면이 같다. */}
      <ReviewFeedback conversionId={conversion.id} onSubmitted={setFeedbackSubmittedAt} />

      <section
        className="overflow-x-auto rounded-[12px] border border-border bg-card p-5"
        aria-labelledby="masked-heading"
      >
        <h2 className="font-bold" id="masked-heading">
          가린 개인정보
        </h2>
        {conversion.masked_items.length === 0 ? (
          <p className="field-hint">가린 개인정보가 없습니다.</p>
        ) : (
          <table className="masked-table">
            <caption>
              변환 전에 가린 항목입니다. 결과의 자리표시자를 원래 값으로 바꿔 확인하세요. 내려받는
              파일에서는 자동으로 원래 값이 들어갑니다.
            </caption>
            <thead>
              <tr>
                <th scope="col">종류</th>
                <th scope="col">자리표시자</th>
                <th scope="col">원래 값</th>
                <th scope="col">결과에 있는지</th>
              </tr>
            </thead>
            <tbody>
              {conversion.masked_items.map((item) => (
                <tr key={item.placeholder}>
                  <td>{item.category}</td>
                  <td>
                    <code>{item.placeholder}</code>
                  </td>
                  <td>{item.original}</td>
                  {/* 지금 고치고 있는 글을 기준으로 본다 — 저장 전 수정으로 자리표시자를
                      지웠다면 그 자리에서 알아야 한다. */}
                  <td>{draft.includes(item.placeholder) ? '있음' : '없음'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </section>
  )
}
