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

  function load(example: DocumentExample) {
    onLoad(example)
    setPending(null)
    setLoaded(`${example.category} 원문을 불러왔습니다. 내용을 확인한 뒤 변환해 주세요.`)
    triggerRef.current?.focus()
  }

  return (
    <section className="rounded-xl bg-muted/60 p-4" aria-label="예시 불러오기">
      <h2 className="text-sm font-bold text-foreground">예시 불러오기</h2>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">
        문서가 없다면 가상 원문으로 시작해 보세요. 불러오기는 무료이며, 변환할 때 크레딧을
        사용합니다.
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        {DOCUMENT_EXAMPLES.map((example) => (
          <Button
            key={example.id}
            type="button"
            variant="outline"
            size="sm"
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
            입력한 제목·글·선택한 파일을 지우고 {pending.category} 예시로 바꿀까요?
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
      {loaded !== null && (
        <p className="mt-3 text-sm leading-6 text-brand-foreground" role="status">
          {loaded}
        </p>
      )}
    </section>
  )
}
