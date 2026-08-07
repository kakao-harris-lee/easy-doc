import { useEffect, useId, useRef, useState } from 'react'

import { ApiError, downloadExport, saveReview } from '../api/client'
import type { ConversionResponse, ExportFormat } from '../api/types'
import { setUnsavedChanges } from '../review/unsavedChanges'

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

/** 저장·내려받기 결과 안내. 성공과 실패의 낭독 방식이 달라 종류를 함께 둔다. */
interface Feedback {
  kind: 'success' | 'error'
  message: string
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
  const initialText = conversion.edited_text ?? conversion.easy_text ?? ''

  const [draft, setDraft] = useState(initialText)
  /** 마지막으로 서버에 저장된 글. 이것과 draft가 다르면 저장하지 않은 변경이다. */
  const [savedText, setSavedText] = useState(initialText)
  const [reviewedAt, setReviewedAt] = useState(conversion.reviewed_at)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [busy, setBusy] = useState(false)

  const dirty = draft !== savedText

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

  async function handleSave(): Promise<void> {
    setBusy(true)
    setFeedback(null)
    try {
      const saved = await saveReview(conversion.id, draft)
      // 서버가 다듬은 결과(제어문자 제거 등)를 그대로 화면에 반영한다 — 우리가 보낸
      // 글을 저장본으로 삼으면 저장 직후에도 "수정됨" 표시가 남는 경우가 생긴다.
      const stored = saved.edited_text ?? draft
      setDraft(stored)
      setSavedText(stored)
      setReviewedAt(saved.reviewed_at)
      setFeedback({ kind: 'success', message: '검수 내용을 저장했습니다.' })
    } catch (caught) {
      setFeedback({
        kind: 'error',
        message:
          caught instanceof ApiError
            ? caught.message
            : '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      })
    } finally {
      setBusy(false)
    }
  }

  async function handleDownload(format: ExportFormat): Promise<void> {
    setBusy(true)
    setFeedback(null)
    try {
      const downloaded = await downloadExport(conversion.id, format)
      saveBlob(downloaded.blob, downloaded.filename ?? `쉬운 글.${format}`)
      setFeedback({ kind: 'success', message: `${format.toUpperCase()} 파일을 내려받았습니다.` })
    } catch (caught) {
      // 자리표시자가 빠진 초안은 내려받을 수 없다(409) — 그 사유도 백엔드 문구로 온다.
      setFeedback({
        kind: 'error',
        message:
          caught instanceof ApiError
            ? caught.message
            : '파일을 내려받지 못했습니다. 잠시 후 다시 시도해 주세요.',
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="review" aria-labelledby="review-heading">
      {/* HITL 고지 — 이 화면에서 가장 먼저 읽혀야 하는 문장이다. */}
      <p className="review-banner" role="note">
        AI가 만든 초안입니다 — 반드시 검토 후 사용하세요.
      </p>

      <h2 id="review-heading" ref={headingRef} tabIndex={-1}>
        쉬운 글 검수
      </h2>

      {conversion.missing_placeholders.length > 0 && (
        <p className="review-warning">
          <strong>주의:</strong> 가린 개인정보 자리표시자{' '}
          {conversion.missing_placeholders.join(', ')}가 결과에서 빠졌습니다. 해당 내용이 필요하면
          아래 표를 보고 직접 넣어 주세요. 자리표시자가 빠진 채로는 파일을 내려받을 수 없습니다.
        </p>
      )}

      <div className="review-split">
        <div className="review-pane">
          <h3>
            <label htmlFor={`${editorId}-source`}>원본 (읽기 전용)</label>
          </h3>
          {sourceText === null ? (
            <p className="field-hint">
              이 화면에서는 원본을 볼 수 없습니다. 파일로 올렸거나 변환 기록에서 다시 열었기
              때문입니다. 가린 개인정보는 아래 표에서 확인할 수 있습니다.
            </p>
          ) : (
            // 읽기 전용 textarea로 두면 키보드로 초점을 받아 스크롤·선택·복사까지 된다 —
            // 스크롤되는 div에 tabindex를 붙이는 것보다 조작 방법이 분명하다.
            <textarea
              id={`${editorId}-source`}
              className="review-textarea review-source"
              value={sourceText}
              rows={20}
              readOnly
            />
          )}
        </div>

        <div className="review-pane">
          <h3>
            <label htmlFor={editorId}>쉬운 글 결과 (고칠 수 있습니다)</label>
          </h3>
          <textarea
            id={editorId}
            className="review-textarea"
            value={draft}
            rows={20}
            onChange={(event) => setDraft(event.target.value)}
          />
        </div>
      </div>

      <div className="review-actions">
        <button type="button" onClick={() => void handleSave()} disabled={busy}>
          {busy ? '처리 중…' : '검수 내용 저장'}
        </button>
        <button type="button" onClick={() => void handleDownload('docx')} disabled={busy}>
          docx 내려받기
        </button>
        <button type="button" onClick={() => void handleDownload('txt')} disabled={busy}>
          txt 내려받기
        </button>
      </div>

      {/* 저장 결과는 화면 어디를 보고 있든 알아야 한다. 실패는 즉시(alert),
          성공은 하던 일을 끊지 않도록(status) 알린다. */}
      {feedback !== null && (
        <p
          className={feedback.kind === 'error' ? 'form-error' : 'form-success'}
          role={feedback.kind === 'error' ? 'alert' : 'status'}
        >
          {feedback.message}
        </p>
      )}

      <p className="field-hint" role="status">
        {dirty
          ? '저장하지 않은 수정이 있습니다. 내려받는 파일에는 저장한 내용만 담깁니다.'
          : reviewedAt === null
            ? '아직 저장한 검수 내용이 없습니다 (AI 초안 그대로입니다).'
            : `마지막 저장: ${new Date(reviewedAt).toLocaleString('ko-KR')}`}
      </p>

      <section className="masked-panel" aria-labelledby="masked-heading">
        <h3 id="masked-heading">가린 개인정보</h3>
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
