import { useEffect, useId, useRef, useState } from 'react'
import type { ChangeEvent, FormEvent, RefObject } from 'react'
import { ArrowRight, FileCheck2, FileText, Upload, Wand2, X } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'

import {
  ApiError,
  createDocumentFromFile,
  createDocumentFromText,
  listDocuments,
} from '../api/client'
import type { DocumentCreatedResponse, DocumentListItem } from '../api/types'
import { chooseNextAction } from '../conversion/nextAction'
import { conversionPath, type SourceTextState } from '../routes/paths'
import { useWorkspace } from '../workspace/context'
import { Badge } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { PageHeader } from '../components/PageHeader'

/** 한 번에 변환할 수 있는 길이. 백엔드 MAX_CONVERTIBLE_CHARS와 같은 값이다. */
export const MAX_CHARS = 4000

/** 문서 제목 길이 상한. 백엔드 x-input-limits.max_title_length와 같은 값이다. */
const MAX_TITLE_LENGTH = 255

/** 업로드 파일 크기 상한. 백엔드 MAX_UPLOAD_BYTES와 같은 값이다. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

/** 받을 수 있는 확장자. 구버전 hwp·doc는 백엔드가 전용 문구로 거절한다(DocumentExtractors.kt, Ole2Diagnosis.kt). */
export const ACCEPTED_EXTENSIONS = '.docx,.pdf,.hwpx,.txt'

/**
 * 글자 수 카운터가 보조 글자색에서 주의색으로 바뀌는 지점(DESIGN.md §6.2의 "80% 이상").
 *
 * 상한의 몇 %인지가 규칙이므로 3,200이라는 결과값이 아니라 비율을 상수로 둔다 —
 * MAX_CHARS가 바뀌면 경고 지점도 같이 따라가야 한다.
 */
const COUNTER_WARNING_RATIO = 0.8

/** 화면이 안내하는 지원 형식. `accept` 값과 갈리지 않도록 같은 상수에서 만든다. */
const SUPPORTED_FORMAT_LABEL = ACCEPTED_EXTENSIONS.split(',')
  .map((extension) => extension.replace('.', '').toUpperCase())
  .join(' · ')

const BYTES_PER_UNIT = 1024
const SIZE_UNITS = ['B', 'KB', 'MB', 'GB'] as const

/**
 * 「다음 할 일」 판단에 훑어볼 문서 수(§7).
 *
 * 규칙은 상태별 우선순위라 목록의 첫 줄만 봐서는 답이 나오지 않는다 — 최근 문서가 모두
 * 검수까지 끝난 상태라도 그 아래에 검수를 기다리는 문서가 있을 수 있다. 그렇다고 전부
 * 훑을 이유도 없다: 이 화면의 본업은 문서 등록이고 제안은 보조다. 기록 화면의 한 쪽
 * 크기(HistoryPage.PAGE_SIZE)와 같은 20건이면 최근 작업 맥락을 판단하기에 충분하다.
 */
const SUGGESTION_SCAN_LIMIT = 20

/**
 * §6.2 오른쪽 안내 카드의 3단계.
 *
 * 가리는 개인정보는 주민등록번호·카드번호 2종뿐이다 — 계약이 그렇게 고정했으므로
 * 전화번호·이메일까지 가려 준다고 쓰면 화면이 없는 보호를 약속하게 된다.
 */
const GUIDE_STEPS = [
  {
    title: '개인정보 2종 가림',
    detail: '주민등록번호와 카드번호를 자리표시자로 바꾼 뒤 변환합니다.',
  },
  { title: '쉬운 글 초안 생성', detail: '짧은 문장과 쉬운 표현으로 바꾼 초안을 만듭니다.' },
  { title: '담당자 직접 검수', detail: '원문과 나란히 놓고 고쳐 저장합니다.' },
] as const

/**
 * PDF 제한 한 문장(DESIGN.md §6.5 마지막 문단).
 *
 * **올리기 전에** 알려야 하는 사실이라 이 화면이 말한다. PDF 업로드 자체는 정상 지원이고
 * 변환·검수도 그대로 된다 — 못 하는 것은 **같은 형식으로 다시 내려받는 것** 하나뿐이라
 * 그 하나만 적는다. 다른 형식으로 대신 받으라는 우회는 제시하지 않는다(§6.5).
 *
 * 「준비 중」이라고 쓰지 않는다. 이것은 아직 못 만든 기능이 아니라 **하지 않기로 정해진
 * 범위**다 — PDF는 출력용 형식이고 편집본을 PDF로 다시 만드는 일은 이 제품의 몫이 아니다.
 *
 * 안내 카드와 고른 파일 카드가 같은 문장을 쓴다 — 같은 사실을 두 자리에서 다르게 말하면
 * 어느 쪽이 맞는지 사용자가 알 수 없다.
 */
const PDF_EXPORT_LIMIT =
  'PDF는 출력용 형식이라 결과를 같은 PDF 파일로 다시 만들지 않습니다. 업로드와 변환, 검수는 그대로 됩니다.'

/** PDF 확장자 판정. 화면 표시용이라 최종 판단은 서버가 한다(formatOf와 같은 규칙). */
function isPdf(fileName: string): boolean {
  return fileName.toLowerCase().endsWith('.pdf')
}

type InputMode = 'text' | 'file'

/** 상한을 사람이 읽는 표기로. */
function chars(count: number): string {
  return count.toLocaleString('ko-KR')
}

/** 파일 크기를 사람이 읽는 단위로. 10,485,760 → `10MB`. */
function formatBytes(bytes: number): string {
  let value = bytes
  let unit = 0
  while (value >= BYTES_PER_UNIT && unit < SIZE_UNITS.length - 1) {
    value /= BYTES_PER_UNIT
    unit += 1
  }
  const rounded = unit === 0 ? value : Math.round(value * 10) / 10
  return `${rounded.toLocaleString('ko-KR')}${SIZE_UNITS[unit]}`
}

/** 파일명에서 형식을 읽는다. 확장자는 서버가 최종 판단하므로 여기서는 표시만 한다. */
function formatOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot === -1 ? '알 수 없는 형식' : fileName.slice(dot + 1).toUpperCase()
}

interface SelectedFileCardProps {
  file: File
  onRemove: () => void
  /** 파일을 고른 직후 초점을 받을 자리. UploadPage의 선택 경로가 여기로 초점을 옮긴다. */
  cardRef: RefObject<HTMLDivElement>
}

/**
 * 고른 파일의 요약 카드(§6.2).
 *
 * 기본 파일 입력은 브라우저마다 파일명을 자르거나 크기를 아예 안 보여준다. 무엇을
 * 올리려는지가 제출 직전의 유일한 확인 지점이라 이름·형식·크기와 제거 행동을 화면이
 * 직접 그린다. 제거 버튼의 이름에 파일명을 넣는 이유는 목록이 아니어도 낭독기가
 * "무엇을" 지우는지 말해야 하기 때문이다.
 *
 * 카드 자체가 초점을 받는다(`tabIndex={-1}`). 제거 버튼에 초점을 주면 낭독기가 "제거"만
 * 읽어 **무슨 파일이** 선택됐는지 알 수 없다. 그래서 카드에 role="group"과 "선택한 파일
 * <파일명>"이라는 이름을 붙여, 초점이 옮겨오는 것만으로 방금 한 행동의 결과가 읽히게
 * 한다 — 같은 사실을 라이브 영역으로 한 번 더 알리지 않는다.
 * 탭 순서에는 넣지 않는다. 키보드 이동 경로에 초점만 받는 컨테이너가 끼면 제거 버튼까지
 * 가는 길이 한 칸 길어진다.
 */
function SelectedFileCard({ file, onRemove, cardRef }: SelectedFileCardProps) {
  const headingId = useId()
  const nameId = useId()
  return (
    <div
      ref={cardRef}
      tabIndex={-1}
      role="group"
      aria-labelledby={`${headingId} ${nameId}`}
      className="flex flex-col gap-1.5"
    >
      <p id={headingId} className="text-[15px] font-semibold text-foreground">
        선택한 파일
      </p>
      <div className="flex items-center gap-3 rounded-[10px] border border-input bg-card p-3">
        <span
          className="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-accent text-accent-foreground"
          aria-hidden="true"
        >
          <FileText className="size-[18px]" />
        </span>
        <div className="min-w-0 flex-1">
          <p id={nameId} className="truncate font-semibold text-foreground">
            {file.name}
          </p>
          <p className="text-sm text-muted-foreground">
            {formatOf(file.name)} · {formatBytes(file.size)}
          </p>
        </div>
        <Button
          type="button"
          variant="ghost"
          onClick={onRemove}
          aria-label={`${file.name} 파일 제거`}
          className="size-11 shrink-0 px-0"
        >
          <X className="size-[18px]" aria-hidden="true" />
        </Button>
      </div>
      {/* 고른 파일이 PDF일 때만, 올리기 전 마지막 확인 지점에서 같은 사실을 한 번 더
          말한다(§6.5). 안내 카드의 문장을 그대로 쓴다. */}
      {isPdf(file.name) && <p className="field-hint m-0">{PDF_EXPORT_LIMIT}</p>}
    </div>
  )
}

/**
 * 문서 업로드 화면.
 *
 * 붙여넣기와 파일 올리기 중 하나를 고르게 한다 — 백엔드가 한 요청에 하나만 받고,
 * 둘 다 채운 화면은 "무엇이 변환됐는지" 사용자가 헷갈린다.
 *
 * 길이·크기 상한은 서버가 최종 판단하지만 화면에서도 먼저 본다. 상한을 넘은 글을
 * 굳이 올려 보내 422를 받아오는 왕복은 사용자에게 아무것도 알려주지 않는다.
 */
export function UploadPage() {
  const navigate = useNavigate()
  // 지금 고른 작업 공간에 담는다. 아직 목록을 못 받았으면(null) 서버가 기본 작업
  // 공간에 담는다 — 업로드를 막는 대신 늘 갈 곳이 있게 한다.
  const { workspaces, currentId: workspaceId } = useWorkspace()
  const textareaId = useId()
  const titleFieldId = useId()
  const fileId = useId()
  const counterId = useId()
  const overflowId = useId()
  const guideId = useId()

  const [mode, setMode] = useState<InputMode>('text')
  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // 「다음 할 일」의 근거. null은 "아직 모른다"이며(조회 전 또는 조회 실패) 빈 배열은
  // "이 작업 공간에는 문서가 없다"는 서버의 답이다. 두 상태를 한 값으로 합치지 않는다 —
  // §6.2는 완료 상태와 검수 여부가 확인될 때만 제안하라고 했다.
  const [recentDocuments, setRecentDocuments] = useState<DocumentListItem[] | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const fileCardRef = useRef<HTMLDivElement>(null)
  // 제거 버튼을 눌러 파일이 빠졌는지 표시한다. 초점을 되돌릴 시점은 파일 입력이 다시
  // 보이게 된 뒤이므로 렌더가 끝난 다음에 읽는다.
  const refocusPending = useRef(false)
  // 파일을 골라 카드가 새로 나타났는지 표시한다. 선택과 제거는 초점을 옮길 곳도, 옮길
  // 시점도 다르므로 서로 다른 표시와 서로 다른 effect로 둔다 — 한 곳에 엮으면 한쪽
  // 경로가 다른 쪽이 방금 옮긴 초점을 뺏는다.
  const cardFocusPending = useRef(false)

  const charCount = text.length
  const tooLong = charCount > MAX_CHARS
  // 80% 미만에서는 보조 글자색이다. 여유가 많을 때까지 경고색을 쓰면 실제로 위험한
  // 순간에 색이 아무 말도 하지 못한다(§6.2).
  const nearLimit = !tooLong && charCount >= MAX_CHARS * COUNTER_WARNING_RATIO
  const titleTrimmed = title.trim()

  // 헤더 맥락 라벨은 "어느 작업 공간에서 무엇을 하는가"다(§5.3). 목록을 아직 못
  // 받았으면 작업 공간 이름 없이 화면 이름만 남긴다.
  const workspaceName = workspaces.find((workspace) => workspace.id === workspaceId)?.name
  const headerContext = workspaceName === undefined ? '새 변환' : `${workspaceName} · 새 변환`

  // 작업 공간이 바뀌면 이전 작업 공간의 제안을 그 자리에서 내린다. 새 응답이 올 때까지
  // 남겨 두면 방금 옮겨 온 작업 공간에 없는 문서를 이어서 하라고 권하게 된다. 렌더 중에
  // 맞추는 이유는 기록 화면과 같다(React 공식 "렌더 중 상태 조정" 패턴) — effect로 미루면
  // 잘못된 작업 공간의 제안이 한 프레임 먼저 보인다.
  // 아래 조회가 작업 공간이 정해진 뒤에만 나가게 된 지금도 이 처리는 그대로 필요하다:
  // 조회를 아예 걸지 않는 것은 `null`인 동안뿐이고, w1 → w2처럼 **정해진 공간끼리**
  // 바뀌는 경우는 이전 공간의 응답이 이미 상태에 들어와 있다.
  const [suggestionWorkspaceId, setSuggestionWorkspaceId] = useState(workspaceId)
  if (suggestionWorkspaceId !== workspaceId) {
    setSuggestionWorkspaceId(workspaceId)
    setRecentDocuments(null)
  }

  const nextAction = chooseNextAction(recentDocuments)

  /**
   * 「다음 할 일」의 근거가 될 최근 문서를 읽는다(§6.2, §7).
   *
   * 새 API를 만들지 않는다 — 기록 화면이 쓰는 `GET /documents`를 그대로 쓴다. 조회는
   * 현재 작업 공간으로 좁힌다(§3 개인화 우선순위 2).
   *
   * **작업 공간이 정해지기 전(null)에는 아예 부르지 않는다.** 여기서 「기록 화면과 같이
   * 좁히지 않고 부른다」로 되돌리지 마라 — 두 화면은 같은 판단을 공유할 수 없다. 기록
   * 화면은 받은 목록 **그 자체**를 보여주므로 범위가 넓어도 화면이 거짓말하지 않지만,
   * 이 화면은 제안을 **작업 공간 맥락과 짝지어** 보여준다(머리말이 "‘복지정책팀’ · 새
   * 변환"이라고 말한다). 좁히지 않은 응답이 근거로 들어오면 화면이 복지정책팀에서
   * 작업 중이라고 말하면서 다른 작업 공간의 문서 제목을 이어서 검수하라고 권하게 된다.
   *
   * 넓게 불러 두고 나중에 거르는 방식도 쓰지 않는다. 그 시점에는 다른 작업 공간의 문서
   * 제목이 이미 브라우저에 와 있고, 화면에 안 그린다고 새어 나간 사실이 없어지지 않는다.
   * 부르지 않는 대가는 제안이 잠깐 늦게 나타나는 것뿐이다 — 목록이 도착해 `workspaceId`가
   * 정해지면 이 effect가 그때 다시 돈다. 작업 공간이 하나도 없어 끝내 정해지지 않는
   * 계정에서는 조용히 아무것도 제안하지 않는다(오류로 알리지 않는다).
   *
   * 실패하면 조용히 아무것도 제안하지 않는다. 이 제안은 보조이지 이 화면의 핵심 흐름이
   * 아니므로, 실패를 오류로 알리면 정작 해야 할 일(문서 등록)을 가리는 소음이 된다.
   * 어느 갈래에서도 문서 등록은 막지 않는다.
   */
  useEffect(() => {
    if (workspaceId === null) {
      return
    }
    // 이 실행이 근거로 삼는 작업 공간. 좁힐 값이 있다는 사실을 아래 비동기 함수까지
    // 들고 간다 — 조회 시점에 다시 null을 따지는 갈래를 남기지 않는다.
    const scope = workspaceId
    const controller = new AbortController()

    async function loadSuggestion(): Promise<void> {
      try {
        const page = await listDocuments(
          { limit: SUGGESTION_SCAN_LIMIT, workspaceId: scope },
          controller.signal,
        )
        setRecentDocuments(page.items)
      } catch {
        // 취소든 서버 오류든 결론은 같다: 근거가 없으므로 제안하지 않는다.
        setRecentDocuments(null)
      }
    }

    void loadSuggestion()
    return () => controller.abort()
  }, [workspaceId])

  useEffect(() => {
    if (file !== null || !refocusPending.current) {
      return
    }
    // 방금 누른 제거 버튼이 사라졌으므로 초점을 다시 파일 입력으로 돌려준다.
    refocusPending.current = false
    fileInputRef.current?.focus()
  }, [file])

  useEffect(() => {
    if (file === null || !cardFocusPending.current) {
      return
    }
    // 초점을 갖고 있던 파일 입력이 방금 화면에서 내려갔다. 그대로 두면 초점이 <body>로
    // 떨어져 키보드 사용자는 Tab 위치를 잃고 낭독기는 무엇이 선택됐는지 알리지 못한다.
    // 카드가 그려진 뒤인 여기서 결과가 나타난 자리로 초점을 옮긴다.
    cardFocusPending.current = false
    fileCardRef.current?.focus()
  }, [file])

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null
    // 대화상자를 취소해 고른 파일이 없으면 파일 입력이 그대로 보이고 초점도 거기 남는다
    // — 옮길 일이 없다. 제거 경로의 표시는 여기서 접어 두 경로가 겹치지 않게 한다.
    refocusPending.current = false
    cardFocusPending.current = selected !== null
    setFile(selected)
    setError(null)
  }

  /**
   * 고른 파일을 물린다.
   *
   * 상태만 비우면 `input[type=file]`에는 같은 파일이 남아 있어, 방금 지운 파일을
   * 다시 고를 때 change 이벤트가 발생하지 않는다(브라우저는 값이 같으면 알리지 않는다).
   * DOM 값까지 비워야 "제거 → 같은 파일 다시 선택"이 실제로 동작한다.
   */
  function removeFile() {
    if (fileInputRef.current !== null) {
      fileInputRef.current.value = ''
    }
    cardFocusPending.current = false
    refocusPending.current = true
    setFile(null)
    setError(null)
  }

  /**
   * 문서를 등록하고 변환 화면으로 넘어간다.
   *
   * sourceText는 붙여넣기 경로에서만 있다 — 그 글이 분할 화면 왼쪽의 원본이 된다.
   */
  async function submit(
    create: () => Promise<DocumentCreatedResponse>,
    sourceText?: string,
  ): Promise<void> {
    setSubmitting(true)
    try {
      const created = await create()
      const state: SourceTextState = sourceText === undefined ? {} : { sourceText }
      navigate(conversionPath(created.conversion_id), { state })
    } catch (caught) {
      // 백엔드 오류 메시지는 사용자에게 보이려고 만든 한국어 문구다(입력값 미포함).
      // 413(크기 초과)·422(형식·길이)·502(변환 서비스)·503(설정) 모두 여기로 온다.
      setError(
        caught instanceof ApiError
          ? caught.message
          : '문서를 올리지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
      setSubmitting(false)
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setError(null)
    if (titleTrimmed === '') {
      setError('문서 제목을 입력해 주세요.')
      return
    }
    // 제목 길이는 여기서 막지 않는다. 계약(`contracts/easy-doc-v1.yaml` x-title-policy.rule,
    // x-input-limits.max_title_length)이 정한 처분은 **자르기이지 거절이 아니다** — 사용자가
    // 라벨 하나 때문에 문서 접수를 거절당하지 않게 한 결정이다. 입력 칸의 maxLength가 넘치는
    // 입력을 먼저 막고, 그래도 넘어간 제목은 서버가 잘라 저장한다.
    if (mode === 'text') {
      if (text.trim() === '') {
        setError('변환할 글을 입력해 주세요.')
        return
      }
      if (tooLong) {
        setError(`글이 너무 깁니다. ${chars(MAX_CHARS)}자 이내로 줄여 주세요.`)
        return
      }
      await submit(() => createDocumentFromText(text, workspaceId, titleTrimmed), text)
      return
    }
    if (file === null) {
      setError('변환할 파일을 선택해 주세요.')
      return
    }
    if (file.size > MAX_UPLOAD_BYTES) {
      setError(`파일이 너무 큽니다. ${formatBytes(MAX_UPLOAD_BYTES)} 이내 파일로 나눠 올려 주세요.`)
      return
    }
    await submit(() => createDocumentFromFile(file, workspaceId, titleTrimmed))
  }

  function selectMode(next: InputMode) {
    setMode(next)
    setError(null)
  }

  return (
    <section aria-labelledby="upload-heading">
      <PageHeader
        context={headerContext}
        title="문서 변환하기"
        titleId="upload-heading"
        description="어려운 행정·복지 안내문을 쉬운 우리말 초안으로 바꿉니다."
      />

      {/* 1280px 이상에서만 3:2로 나눈다(§10). 그 아래에서는 한 열로 접히고, 안내 카드는
      DOM 순서 그대로 폼 **뒤에** 놓여 입력과 대표 버튼 사이에 끼지 않는다. */}
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(18rem,2fr)]">
        <form
          className="rounded-2xl border border-border bg-card shadow-[0_8px_28px_rgba(35,31,70,0.06)]"
          onSubmit={(event) => void handleSubmit(event)}
          noValidate
        >
          <div className="border-b border-border px-5 py-4 sm:px-6">
            <h2 className="text-[17px] font-bold leading-6 text-foreground">원문 입력</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              글을 붙여넣거나 문서 파일을 올려 주세요.
            </p>
          </div>
          <div className="flex flex-col gap-5 px-4 py-5 sm:px-6">
            {error !== null && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}

            <div className="field">
              <label htmlFor={titleFieldId}>문서 제목</label>
              <input
                id={titleFieldId}
                type="text"
                value={title}
                maxLength={MAX_TITLE_LENGTH}
                placeholder="예: 2024년 청년 월세 특별지원 안내문"
                onChange={(event) => setTitle(event.target.value)}
              />
              {/* aria-invalid를 길이로 걸지 않는다 — 상한을 넘긴 제목도 거절이 아니라 잘림이라
              「잘못된 값」이라는 안내가 실제 처분과 어긋난다. maxLength가 입력을 돕는다. */}
              <p className="field-hint">
                변환 기록에서 문서를 구분하는 이름입니다. {MAX_TITLE_LENGTH}자 이내.
              </p>
            </div>

            {/* 탭처럼 보이되 라디오다(§6.2) — 화살표 키 이동과 그룹 이름(legend)을 지킨다. */}
            <fieldset className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <legend className="col-span-full mb-1 text-[15px] font-semibold">
                변환할 내용을 어떻게 넣을까요?
              </legend>
              <label
                className={`flex min-h-12 cursor-pointer items-center gap-2 rounded-[10px] border px-3.5 font-semibold ${mode === 'text' ? 'border-primary bg-accent text-accent-foreground' : 'border-input bg-background'}`}
              >
                <input
                  className="accent-primary"
                  type="radio"
                  name="input-mode"
                  value="text"
                  checked={mode === 'text'}
                  onChange={() => selectMode('text')}
                />
                <FileText className="size-[18px]" aria-hidden="true" />글 붙여넣기
              </label>
              <label
                className={`flex min-h-12 cursor-pointer items-center gap-2 rounded-[10px] border px-3.5 font-semibold ${mode === 'file' ? 'border-primary bg-accent text-accent-foreground' : 'border-input bg-background'}`}
              >
                <input
                  className="accent-primary"
                  type="radio"
                  name="input-mode"
                  value="file"
                  checked={mode === 'file'}
                  onChange={() => selectMode('file')}
                />
                <Upload className="size-[18px]" aria-hidden="true" />
                파일 올리기
              </label>
            </fieldset>

            {mode === 'text' ? (
              <div className="field">
                <label htmlFor={textareaId}>바꿀 글</label>
                <textarea
                  id={textareaId}
                  className="upload-textarea min-h-80"
                  value={text}
                  rows={14}
                  aria-describedby={tooLong ? `${overflowId} ${counterId}` : counterId}
                  aria-invalid={tooLong}
                  onChange={(event) => setText(event.target.value)}
                />
                {/* 글자 수 안내를 라이브 영역으로 두지 않는다 — 한 글자마다 낭독기가 숫자를
                읽고, 상한을 넘으면 제출 오류와 같은 말을 두 번 알리게 된다. 입력 칸이
                aria-describedby로 이 문단을 가리키고, 넘긴 사실은 aria-invalid와 제출
                시점의 오류(role="alert")가 알린다. */}
                <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                  {tooLong && (
                    <p id={overflowId} className="field-error">
                      상한을 넘었습니다. 문서를 나눠 변환해 주세요.
                    </p>
                  )}
                  <p
                    id={counterId}
                    className={`m-0 ml-auto text-sm tabular-nums ${
                      tooLong
                        ? 'font-medium text-danger'
                        : nearLimit
                          ? 'font-medium text-warning'
                          : 'text-muted-foreground'
                    }`}
                  >
                    {chars(charCount)} / {chars(MAX_CHARS)}자
                  </p>
                </div>
              </div>
            ) : (
              <>
                {/* 파일을 고르면 이 묶음을 화면에서 내리되 DOM에는 남긴다 — 같은 파일을
                다시 고를 수 있게 하려면 removeFile이 이 입력의 value를 비워야 한다. */}
                <div className={file === null ? 'field' : 'hidden'}>
                  <label htmlFor={fileId}>바꿀 파일</label>
                  <input
                    id={fileId}
                    ref={fileInputRef}
                    type="file"
                    accept={ACCEPTED_EXTENSIONS}
                    aria-describedby={`${fileId}-hint`}
                    onChange={handleFileChange}
                  />
                  <p className="field-hint" id={`${fileId}-hint`}>
                    {SUPPORTED_FORMAT_LABEL} 파일, {formatBytes(MAX_UPLOAD_BYTES)} 이내. 파일에서
                    뽑은 글자 수가 {chars(MAX_CHARS)}자를 넘으면 변환할 수 없습니다.
                  </p>
                </div>
                {file !== null && (
                  <SelectedFileCard file={file} onRemove={removeFile} cardRef={fileCardRef} />
                )}
              </>
            )}

            <Button type="submit" size="lg" loading={submitting} className="w-full sm:w-fit">
              <Wand2 className="size-[18px]" aria-hidden="true" />
              {submitting ? '올리는 중…' : '쉬운 글 초안 만들기'}
            </Button>
          </div>
        </form>

        <aside>
          <section
            className="rounded-2xl border border-border bg-card p-5 sm:p-6"
            aria-labelledby={guideId}
          >
            {/* 대표 행동은 폼의 제출 버튼 하나뿐이다(§5.3). 헤더 대신 이 카드가 AI 초안
            이라는 사실을 알린다 — 안내 카드가 그 사실이 필요한 자리다. */}
            <Badge tone="primary" withIcon={false}>
              <FileCheck2 className="size-4" aria-hidden="true" />
              AI 초안 · 사람 검토 필수
            </Badge>
            <h2 id={guideId} className="mt-4 text-[17px] font-bold leading-6 text-foreground">
              이 작업에서 일어나는 일
            </h2>
            <ol className="mt-4 flex flex-col gap-4">
              {GUIDE_STEPS.map((step, index) => (
                <li key={step.title} className="flex items-start gap-3">
                  <span
                    className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-bold text-accent-foreground"
                    aria-hidden="true"
                  >
                    {index + 1}
                  </span>
                  <div className="flex flex-col">
                    <strong className="text-[15px] font-semibold text-foreground">
                      {step.title}
                    </strong>
                    <small className="text-sm leading-[22px] text-muted-foreground">
                      {step.detail}
                    </small>
                  </div>
                </li>
              ))}
            </ol>
            {/* 지원 형식·크기는 코드 상수에서 만든다 — 안내 문구와 실제 상한이 갈리면
            사용자는 화면을 믿고 거절당한다. */}
            <dl className="mt-5 flex flex-col gap-2 border-t border-border pt-4 text-sm">
              <div className="flex gap-3">
                <dt className="w-[4.5rem] shrink-0 text-muted-foreground">지원 형식</dt>
                <dd className="font-medium text-foreground">{SUPPORTED_FORMAT_LABEL}</dd>
              </div>
              <div className="flex gap-3">
                <dt className="w-[4.5rem] shrink-0 text-muted-foreground">파일 크기</dt>
                <dd className="font-medium text-foreground">
                  {formatBytes(MAX_UPLOAD_BYTES)} 이내
                </dd>
              </div>
              <div className="flex gap-3">
                <dt className="w-[4.5rem] shrink-0 text-muted-foreground">글자 수</dt>
                <dd className="font-medium text-foreground">한 번에 {chars(MAX_CHARS)}자까지</dd>
              </div>
            </dl>
            {/*
              PDF로 올린 결과를 PDF로 다시 받을 수 없다는 사실은 **올리기 전에** 알려야
              한다(§6.5). 자리는 이 보조 안내 카드의 맨 아래다 — 형식별 내려받기 결과를
              말하는 곳이 여기이고, 이 화면의 주 행동(`쉬운 글 초안 만들기`)에서 떨어져 있다.
              실행 버튼도, 곧 될 것처럼 읽히는 표식도 두지 않는다.
            */}
            <p className="mt-4 border-t border-border pt-4 text-sm leading-[22px] text-muted-foreground">
              {PDF_EXPORT_LIMIT} DOCX·HWPX는 원본 서식을 유지한 같은 형식 파일로 내려받습니다.
            </p>
          </section>
        </aside>
      </div>

      {/*
        규칙 기반 「다음 할 일」 한 건(§7).

        `newConversion` 제안(열 변환이 없는 경우)은 여기서 그리지 않는다 — 이 화면이 곧
        새 변환이고, 그 제안은 바로 위 제출 버튼과 같은 말을 두 번 하는 것이다(§15의 3:
        "화면에 이미 더 강한 대표 행동이 있는가?"). 그래서 화면에 나타나는 제안은 언제나
        0개 또는 1개이며, 있을 때도 대표 행동보다 약하다: 페이지 맨 아래, 구분선 하나,
        채운 버튼이 아닌 글자 크기의 링크다(§5.3, §6.2, §14). 터치 대상 44px은 §10을
        따라 링크 높이로 지킨다.
      */}
      {nextAction !== null && nextAction.conversionId !== null && (
        <aside
          aria-label="다음 할 일"
          className="mt-8 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-t border-border pt-4"
        >
          <p className="m-0 text-sm text-muted-foreground">
            <span className="font-semibold text-foreground">‘{nextAction.documentTitle}’</span>{' '}
            {nextAction.message}
          </p>
          <Link
            to={conversionPath(nextAction.conversionId)}
            aria-label={`‘${nextAction.documentTitle}’ ${nextAction.actionLabel}`}
            className="inline-flex min-h-11 items-center gap-1 text-sm font-semibold text-primary underline underline-offset-4 hover:text-primary-hover"
          >
            {nextAction.actionLabel}
            <ArrowRight className="size-[18px]" aria-hidden="true" />
          </Link>
        </aside>
      )}
    </section>
  )
}
