import { useId, useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'

import { ApiError, createDocumentFromFile, createDocumentFromText } from '../api/client'
import type { DocumentCreatedResponse } from '../api/types'
import { conversionPath, type SourceTextState } from '../routes/paths'
import { useWorkspace } from '../workspace/context'

/** 한 번에 변환할 수 있는 길이. 백엔드 MAX_CONVERTIBLE_CHARS와 같은 값이다. */
const MAX_CHARS = 4000

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
  const fileId = useId()
  const counterId = useId()

  const [mode, setMode] = useState<InputMode>('text')
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const charCount = text.length
  const tooLong = charCount > MAX_CHARS

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
    if (mode === 'text') {
      if (text.trim() === '') {
        setError('변환할 글을 입력해 주세요.')
        return
      }
      if (tooLong) {
        setError(`글이 너무 깁니다. ${chars(MAX_CHARS)}자 이내로 줄여 주세요.`)
        return
      }
      await submit(() => createDocumentFromText(text, workspaceId), text)
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
    await submit(() => createDocumentFromFile(file, workspaceId))
  }

  function selectMode(next: InputMode) {
    setMode(next)
    setError(null)
  }

  return (
    <section className="upload-page" aria-labelledby="upload-heading">
      <h2 id="upload-heading">문서 변환하기</h2>
      <p className="page-lead">
        공공기관 안내문을 쉬운 글로 바꿉니다. 한 번에 {chars(MAX_CHARS)}자까지 변환할 수 있습니다.
      </p>

      <form onSubmit={(event) => void handleSubmit(event)} noValidate>
        {error !== null && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <fieldset className="mode-choice">
          <legend>변환할 내용을 어떻게 넣을까요?</legend>
          <label>
            <input
              type="radio"
              name="input-mode"
              value="text"
              checked={mode === 'text'}
              onChange={() => selectMode('text')}
            />
            글 붙여넣기
          </label>
          <label>
            <input
              type="radio"
              name="input-mode"
              value="file"
              checked={mode === 'file'}
              onChange={() => selectMode('file')}
            />
            파일 올리기
          </label>
        </fieldset>

        {mode === 'text' ? (
          <div className="field">
            <label htmlFor={textareaId}>바꿀 글</label>
            <textarea
              id={textareaId}
              className="upload-textarea"
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
              docx·pdf·hwpx 파일, 10MB 이내. 파일에서 뽑은 글자 수가 {chars(MAX_CHARS)}자를 넘으면
              변환할 수 없습니다.
            </p>
          </div>
        )}

        <button type="submit" disabled={submitting}>
          {submitting ? '올리는 중…' : '쉬운 글로 바꾸기'}
        </button>
      </form>
    </section>
  )
}
