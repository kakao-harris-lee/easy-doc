import { useRef, useState } from 'react'

import { DOCUMENT_EXAMPLES, type DocumentExample } from '../content/documentExamples'
import { Button } from './ui/Button'

export function DocumentExamplePicker({
  hasInput,
  disabled,
  onLoad,
}: {
  readonly hasInput: boolean
  readonly disabled: boolean
  readonly onLoad: (example: DocumentExample) => void
}) {
  const [pending, setPending] = useState<DocumentExample | null>(null)
  const [loaded, setLoaded] = useState<string | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const detailsRef = useRef<HTMLDetailsElement>(null)
  const summaryRef = useRef<HTMLElement>(null)

  function load(example: DocumentExample) {
    onLoad(example)
    setPending(null)
    setLoaded(`${example.category} 원문을 불러왔습니다.`)
    if (detailsRef.current !== null) detailsRef.current.open = false
    summaryRef.current?.focus()
  }

  return (
    <section aria-label="예시 불러오기">
      <details
        ref={detailsRef}
        className="rounded-xl bg-muted/60"
        onToggle={() => {
          if (detailsRef.current?.open === false) setPending(null)
        }}
      >
        <summary
          ref={summaryRef}
          className="min-h-11 cursor-pointer px-3 py-3 text-sm font-semibold text-foreground"
        >
          예시 불러오기
        </summary>
        <div className="px-3 pb-3">
          <p className="text-sm leading-6 text-muted-foreground">
            가상 원문 · 불러오기 무료 · 변환 시 크레딧 사용
          </p>
          <div className="mt-3 grid grid-cols-3 gap-1 sm:gap-2">
            {DOCUMENT_EXAMPLES.map((example) => (
              <Button
                key={example.id}
                type="button"
                variant="outline"
                size="sm"
                className="px-1 text-xs sm:px-3 sm:text-sm"
                disabled={disabled}
                onClick={(event) => {
                  triggerRef.current = event.currentTarget
                  setLoaded(null)
                  if (hasInput) setPending(example)
                  else load(example)
                }}
              >
                {example.category}
              </Button>
            ))}
          </div>
          {pending !== null && (
            <div
              className="mt-4 rounded-lg border border-warning/30 bg-warning-surface p-3"
              role="alert"
            >
              <p className="text-sm leading-6 text-foreground">
                {pending.category} 예시로 바꿀까요? 입력한 제목·글·파일 선택이 사라집니다.
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button type="button" size="sm" disabled={disabled} onClick={() => load(pending)}>
                  예시로 바꾸기
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={disabled}
                  onClick={() => {
                    setPending(null)
                    triggerRef.current?.focus()
                  }}
                >
                  취소
                </Button>
              </div>
            </div>
          )}
        </div>
      </details>
      {loaded !== null && (
        <p className="mt-2 text-sm leading-6 text-brand-foreground" role="status">
          {loaded}
        </p>
      )}
    </section>
  )
}
