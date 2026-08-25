import { useId, useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import { FileCheck2, FileText, ShieldCheck, Upload, Wand2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

import { ApiError, createDocumentFromFile, createDocumentFromText } from '../api/client'
import type { DocumentCreatedResponse } from '../api/types'
import { conversionPath, type SourceTextState } from '../routes/paths'
import { useWorkspace } from '../workspace/context'
import { Badge } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'

/** 한 번에 변환할 수 있는 길이. 백엔드 MAX_CONVERTIBLE_CHARS와 같은 값이다. */
const MAX_CHARS = 4000

/** 문서 제목 길이 상한. 백엔드 x-input-limits.max_title_length와 같은 값이다. */
const MAX_TITLE_LENGTH = 255

/** 업로드 파일 크기 상한. 백엔드 MAX_UPLOAD_BYTES와 같은 값이다. */
const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

/** 받을 수 있는 확장자. 구버전 hwp는 백엔드가 거절한다(app/ingest/extractors.py). */
const ACCEPTED_EXTENSIONS = '.docx,.pdf,.hwpx'

type InputMode = 'text' | 'file'

/** 상한을 사람이 읽는 표기로. */
function chars(count: number): string {
  return count.toLocaleString('ko-KR')
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
  const { currentId: workspaceId } = useWorkspace()
  const textareaId = useId()
  const titleId = useId()
  const fileId = useId()
  const counterId = useId()

  const [mode, setMode] = useState<InputMode>('text')
  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const charCount = text.length
  const tooLong = charCount > MAX_CHARS
  const titleTrimmed = title.trim()
  const titleTooLong = title.length > MAX_TITLE_LENGTH

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null)
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
    if (titleTooLong) {
      setError(`제목이 너무 깁니다. ${MAX_TITLE_LENGTH}자 이내로 줄여 주세요.`)
      return
    }
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
      setError('파일이 너무 큽니다. 10MB 이내 파일로 나눠 올려 주세요.')
      return
    }
    await submit(() => createDocumentFromFile(file, workspaceId, titleTrimmed))
  }

  function selectMode(next: InputMode) {
    setMode(next)
    setError(null)
  }

  return (
    <section className="flex flex-col gap-6" aria-labelledby="upload-heading">
      <header className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <div>
          <p className="mb-1 text-sm font-bold text-primary">새 변환</p>
          <h2
            id="upload-heading"
            className="text-2xl font-extrabold tracking-tight text-foreground"
          >
            문서 변환하기
          </h2>
          <p className="mt-1 text-[15px] leading-relaxed text-muted-foreground">
            어려운 행정·복지 안내문을 쉬운 우리말 초안으로 바꿉니다.
          </p>
        </div>
        <Badge tone="primary" withIcon={false}>
          <FileCheck2 className="size-4" aria-hidden="true" />
          AI 초안 · 사람 검토 필수
        </Badge>
      </header>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(18rem,2fr)]">
        <form
          className="rounded-[12px] border border-border bg-card shadow-[0_1px_2px_rgba(20,33,31,0.04)]"
          onSubmit={(event) => void handleSubmit(event)}
          noValidate
        >
          <div className="border-b border-border px-5 py-4">
            <h3 className="text-lg font-bold text-foreground">원문 입력</h3>
            <p className="text-sm text-muted-foreground">
              글을 붙여넣거나 문서 파일을 올려 주세요.
            </p>
          </div>
          <div className="flex flex-col gap-5 px-5 py-5">
            {error !== null && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}

            <div className="field">
              <label htmlFor={titleId}>문서 제목</label>
              <input
                id={titleId}
                type="text"
                value={title}
                maxLength={MAX_TITLE_LENGTH}
                aria-invalid={titleTooLong}
                placeholder="예: 2024년 청년 월세 특별지원 안내문"
                onChange={(event) => setTitle(event.target.value)}
              />
              <p className={titleTooLong ? 'field-error' : 'field-hint'}>
                변환 기록에서 문서를 구분하는 이름입니다. {MAX_TITLE_LENGTH}자 이내.
              </p>
            </div>

            <fieldset className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <legend className="col-span-full mb-1 text-[15px] font-semibold">
                변환할 내용을 어떻게 넣을까요?
              </legend>
              <label
                className={`flex h-12 cursor-pointer items-center gap-2 rounded-[10px] border px-3.5 font-semibold ${mode === 'text' ? 'border-primary bg-accent text-accent-foreground' : 'border-border bg-background'}`}
              >
                <input
                  className="accent-primary"
                  type="radio"
                  name="input-mode"
                  value="text"
                  checked={mode === 'text'}
                  onChange={() => selectMode('text')}
                />
                <FileText className="size-4" aria-hidden="true" />글 붙여넣기
              </label>
              <label
                className={`flex h-12 cursor-pointer items-center gap-2 rounded-[10px] border px-3.5 font-semibold ${mode === 'file' ? 'border-primary bg-accent text-accent-foreground' : 'border-border bg-background'}`}
              >
                <input
                  className="accent-primary"
                  type="radio"
                  name="input-mode"
                  value="file"
                  checked={mode === 'file'}
                  onChange={() => selectMode('file')}
                />
                <Upload className="size-4" aria-hidden="true" />
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
                  aria-describedby={counterId}
                  aria-invalid={tooLong}
                  onChange={(event) => setText(event.target.value)}
                />
                {/* 글자 수 안내를 라이브 영역으로 두지 않는다 — 한 글자마다 낭독기가 숫자를
                읽고, 상한을 넘으면 제출 오류와 같은 말을 두 번 알리게 된다. 입력 칸이
                aria-describedby로 이 문단을 가리키고, 넘긴 사실은 aria-invalid와 제출
                시점의 오류(role="alert")가 알린다. */}
                <p id={counterId} className={tooLong ? 'field-error' : 'field-hint'}>
                  {chars(charCount)} / {chars(MAX_CHARS)}자
                  {tooLong && ' — 상한을 넘었습니다. 문서를 나눠 변환해 주세요.'}
                </p>
              </div>
            ) : (
              <div className="field">
                <label htmlFor={fileId}>바꿀 파일</label>
                <input
                  id={fileId}
                  type="file"
                  accept={ACCEPTED_EXTENSIONS}
                  aria-describedby={`${fileId}-hint`}
                  onChange={handleFileChange}
                />
                <p className="field-hint" id={`${fileId}-hint`}>
                  docx·pdf·hwpx 파일, 10MB 이내. 파일에서 뽑은 글자 수가 {chars(MAX_CHARS)}자를
                  넘으면 변환할 수 없습니다.
                </p>
              </div>
            )}

            <Button type="submit" size="lg" loading={submitting} className="w-fit">
              <Wand2 className="size-4" aria-hidden="true" />
              {submitting ? '올리는 중…' : '쉬운 글로 바꾸기'}
            </Button>
          </div>
        </form>
        <aside className="flex flex-col gap-4">
          <section
            className="rounded-[12px] border border-border bg-card p-5"
            aria-labelledby="guide-heading"
          >
            <span
              className="mb-3 flex size-10 items-center justify-center rounded-[10px] bg-accent text-primary"
              aria-hidden="true"
            >
              <ShieldCheck className="size-5" />
            </span>
            <h3 id="guide-heading" className="font-bold">
              변환 전에 확인해 주세요
            </h3>
            <ul className="mt-3 flex list-disc flex-col gap-2 pl-5 text-sm leading-relaxed text-muted-foreground">
              <li>주민등록번호나 연락처 같은 개인정보는 입력하지 마세요.</li>
              <li>지원 형식은 DOCX, PDF, HWPX이며 파일은 10MB 이내여야 합니다.</li>
              <li>한 번에 공백 포함 {chars(MAX_CHARS)}자까지 변환할 수 있습니다.</li>
            </ul>
          </section>
          <section
            className="rounded-[12px] border border-border bg-card p-5"
            aria-labelledby="process-heading"
          >
            <h3 id="process-heading" className="font-bold">
              이렇게 진행됩니다
            </h3>
            <ol className="mt-4 flex flex-col gap-4">
              <li className="flex items-start gap-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-bold text-primary">
                  1
                </span>
                <div className="flex flex-col">
                  <strong>원문 등록</strong>
                  <small className="text-muted-foreground">텍스트 또는 파일을 올립니다.</small>
                </div>
              </li>
              <li className="flex items-start gap-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-bold text-primary">
                  2
                </span>
                <div className="flex flex-col">
                  <strong>AI 초안 생성</strong>
                  <small className="text-muted-foreground">
                    쉬운 표현과 짧은 문장으로 바꿉니다.
                  </small>
                </div>
              </li>
              <li className="flex items-start gap-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-bold text-primary">
                  3
                </span>
                <div className="flex flex-col">
                  <strong>담당자 검수</strong>
                  <small className="text-muted-foreground">원문과 비교하고 고쳐 저장합니다.</small>
                </div>
              </li>
            </ol>
          </section>
        </aside>
      </div>
    </section>
  )
}
