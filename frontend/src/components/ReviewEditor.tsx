import { useEffect, useId, useRef, useState, useSyncExternalStore, type KeyboardEvent } from 'react'
import { Download, FileX2, Save, ShieldAlert } from 'lucide-react'

import { ApiError, downloadExport, saveReview } from '../api/client'
import type { ConversionResponse, ExportFormat } from '../api/types'
import { cn } from '../lib/utils'
import { setUnsavedChanges } from '../review/unsavedChanges'
import { FormatPreservationPanel, PdfExportNotice } from './FormatPreservationPanel'
import { ReviewFeedback } from './ReviewFeedback'
import { Badge } from './ui/Badge'
import { Button } from './ui/Button'

interface ReviewEditorProps {
  conversion: ConversionResponse
  /**
   * 왼쪽에 보여줄 원본. 붙여넣기로 올린 직후에만 있다.
   *
   * 서버는 원문을 돌려주지 않는다 — 조회 응답에 문서 본문을 실으면 개인정보가
   * 오가는 표면이 넓어진다. 그래서 파일 업로드와 기록에서 다시 들어온 경로에는
   * 원본이 없고, 화면이 그 사실을 사용자에게 알린다.
   */
  sourceText: string | null
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
 * 이 변환을 내려받을 수 있는 형식.
 *
 * **목록이 아니라 서버가 정한 값 하나다**(DESIGN.md §6.5 「들어온 형식 그대로 나간다」).
 * 종전에는 `['docx','hwpx','txt']` 상수라 원본과 무관하게 버튼 셋을 그렸고, 서버가 형식을
 * 강제하기 시작한 뒤로 그중 둘은 **반드시 409로 실패한다.**
 *
 * `export_format`이 null이면 빈 목록이다 — 내려받을 수단이 없는 변환(원본 PDF)에서는
 * 내려받기 행동을 제시하지 않는다(§6.5 "화면은 이 null을 보고 내려받기 행동을 제시하지
 * 않는다"). 버튼이 없는 이유는 `PdfExportNotice`가 그 자리 위에서 말한다.
 */
function downloadFormats(conversion: ConversionResponse): readonly ExportFormat[] {
  return conversion.export_format === null ? [] : [conversion.export_format]
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
export function ReviewEditor({ conversion, sourceText }: ReviewEditorProps) {
  const editorId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const tabRefs = useRef<Partial<Record<PanelKey, HTMLButtonElement | null>>>({})
  const initialText = conversion.edited_text ?? conversion.easy_text ?? ''

  const [draft, setDraft] = useState(initialText)
  /** 마지막으로 서버에 저장된 글. 이것과 draft가 다르면 저장하지 않은 변경이다. */
  const [savedText, setSavedText] = useState(initialText)
  const [reviewedAt, setReviewedAt] = useState(conversion.reviewed_at)
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
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [pending, setPending] = useState<Pending>(null)
  const [activePanel, setActivePanel] = useState<PanelKey>('source')
  /** 저장·내려받기를 누른 버튼. 그 작업이 끝나면 초점을 여기로 돌린다. */
  const refocusRef = useRef<HTMLButtonElement | null>(null)

  const dirty = draft !== savedText
  const busy = pending !== null
  /** 내려받기 버튼이 도는 중인지. 저장을 먼저 하는 경로도 같은 버튼이 돈다. */
  const downloading = pending === 'download' || pending === 'saveAndDownload'
  const splitView = useSplitView()

  /**
   * 좁은 화면에서 탭으로 바꿀지.
   *
   * 원문이 없으면 탭을 만들지 않는다. 고를 수 있는 것이 하나뿐인 탭 줄은 조작할 이유가
   * 없는 장치이고, 원문 없음 설명을 탭 뒤에 숨기면 "아직 안 왔음"과 구분되지 않는다
   * (§9 — 빈 상태·로딩·원문 없음은 서로 다른 상태다). 그래서 이 경로에서는 설명 카드와
   * 편집기를 위아래로 그대로 쌓는다.
   */
  const showTabs = !splitView && sourceText !== null
  const statusId = `${editorId}-save-status`

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
    const saved = await saveReview(conversion.id, draft)
    // 서버가 다듬은 결과(제어문자 제거 등)를 그대로 화면에 반영한다 — 우리가 보낸
    // 글을 저장본으로 삼으면 저장 직후에도 "수정됨" 표시가 남는 경우가 생긴다.
    const stored = saved.edited_text ?? draft
    setDraft(stored)
    setSavedText(stored)
    setReviewedAt(saved.reviewed_at)
    // 방금 저장한 글로 서버가 다시 잰 판정이다. `??`로 옛 값을 붙들지 않는다 — 계약에서
    // 이 키는 늘 있고 `null`은 「아직 판정하지 않았다」라는 서버의 답이라, 그것을 지난
    // 조회의 판정으로 메우면 화면이 서버가 하지 않은 말을 하게 된다.
    setPreservation(saved.format_preservation)
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
          detail: '아직 저장한 검수 내용이 없습니다. AI 초안 그대로입니다.',
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
        <div
          className="flex shrink-0 flex-col items-start gap-1 sm:items-end"
          id={statusId}
          role="status"
        >
          <Badge tone={status.tone}>{status.label}</Badge>
          {status.detail !== null && (
            <span className="text-sm text-muted-foreground sm:text-right">{status.detail}</span>
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
                onClick={() => setActivePanel(panel.key)}
                onKeyDown={handlePanelKeyDown}
              >
                {panel.label}
              </button>
            ))}
          </div>
        )}

        {/* §11: 읽기 순서는 넓은 화면에서도 탭에서도 원문 다음 결과다. */}
        <div className="grid gap-4 lg:grid-cols-2">
          <div
            className="rounded-[12px] border border-border bg-card p-5"
            {...panelProps('source')}
          >
            {sourceText === null ? (
              // 원문 없음은 로딩도 빈 상태도 아니다(§9). 빈 textarea를 만들면 "아직 안
              // 왔음"이나 "지우고 다시 넣어야 함"처럼 보이므로, 왜 없는지 설명하는
              // 카드로 대체한다(§6.4).
              <>
                <h2 className="mb-2 flex items-center gap-2 text-sm font-bold text-muted-foreground">
                  <FileX2 className="size-[18px] shrink-0" aria-hidden="true" />
                  원문 없음
                </h2>
                <div className="rounded-[10px] border border-dashed border-input bg-background p-5">
                  <p className="font-semibold">
                    파일로 올린 문서는 이 화면에서 원문을 다시 표시하지 않습니다.
                  </p>
                  <p className="field-hint mt-2">
                    변환 기록에서 다시 연 문서도 같습니다. 문서 본문을 서버에 다시 받아 오지 않기
                    때문입니다. 가린 개인정보는 아래 대응표에서 확인할 수 있습니다.
                  </p>
                </div>
              </>
            ) : (
              <>
                <h2 className="mb-2 text-sm font-bold text-muted-foreground">
                  <label htmlFor={`${editorId}-source`}>원본 (읽기 전용)</label>
                </h2>
                {/* 읽기 전용 textarea로 두면 키보드로 초점을 받아 스크롤·선택·복사까지 된다 —
                    스크롤되는 div에 tabindex를 붙이는 것보다 조작 방법이 분명하다. */}
                <textarea
                  id={`${editorId}-source`}
                  className="review-textarea review-source text-[17px] leading-[1.75]"
                  value={sourceText}
                  rows={20}
                  readOnly
                />
              </>
            )}
          </div>

          {/* 포인트색 경계로 "여기가 고치는 쪽"임을 원문 패널과 구분한다(§6.4). */}
          <div
            className="rounded-[12px] border-2 border-primary/40 bg-card p-5"
            {...panelProps('result')}
          >
            <div className="mb-2 flex items-center justify-between gap-2">
              <h2 className="text-sm font-bold text-primary">
                <label htmlFor={editorId}>쉬운 글 결과 (고칠 수 있습니다)</label>
              </h2>
              {/* 눈으로 두 패널을 가르는 표식이다. 같은 사실을 위 라벨이 이미 말하므로
                  낭독기에서는 감춘다 — 한 입력에 두 번 붙는 설명이 된다. */}
              <Badge tone="primary" className="shrink-0" aria-hidden="true">
                편집 가능
              </Badge>
            </div>
            <textarea
              id={editorId}
              className="review-textarea text-[17px] leading-[1.75]"
              value={draft}
              rows={20}
              onChange={(event) => setDraft(event.target.value)}
            />
          </div>
        </div>

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
          {downloadFormats(conversion).map((format) => (
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
              loading={downloading}
            >
              {!downloading && <Download className="size-[18px]" aria-hidden="true" />}
              {/* 저장하지 않은 수정이 있으면 두 걸음을 한 버튼 이름으로 말한다(§6.5).
                  형식 이름을 버튼에 넣어 무엇이 나오는지 누르기 전에 알린다 — 누른 뒤
                  형식을 고르게 하는 모달은 두지 않는다. */}
              {pending === 'saveAndDownload'
                ? '저장하고 내려받는 중…'
                : pending === 'download'
                  ? '내려받는 중…'
                  : `${dirty ? '저장하고 ' : ''}${format.toUpperCase()}로 내려받기`}
            </Button>
          ))}
        </div>
      </div>

      {/* 결과를 다 보고 난 자리에 둔다 — 검수 전에 묻는 만족도는 결과가 아니라 기대치를
          재게 된다. 이 화면은 status가 done일 때만 그려지므로(ConversionPage) 서버가
          409로 막는 조건과 화면이 같다. */}
      <ReviewFeedback conversionId={conversion.id} />

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
