import { useEffect, useId, useState } from 'react'

import type { IllustrationSuggestion } from '../api/types'
import { Button } from './ui/Button'

/** 무료 테스트의 화면 흐름 전용. 외부 생성 호출·본문 저장·브라우저 저장소 쓰기는 없다. */
export function IllustrationTestFlow({
  suggestion,
  excerpt,
  disabled,
}: {
  suggestion: IllustrationSuggestion
  excerpt: string
  disabled: boolean
}) {
  const id = useId()
  const [stage, setStage] = useState<'idle' | 'generating' | 'preview' | 'applied'>('idle')
  const [altText, setAltText] = useState(suggestion.alt_text_draft)
  const [reviewed, setReviewed] = useState(false)

  useEffect(() => {
    if (stage !== 'generating' || disabled) return
    const timer = window.setTimeout(() => setStage('preview'), 300)
    return () => window.clearTimeout(timer)
  }, [stage, disabled])

  const picture = (
    <svg
      role="img"
      aria-label={altText.trim() || '테스트 그림'}
      viewBox={`0 0 480 ${suggestion.scenes.length * 100 + 44}`}
      className="w-full max-w-lg rounded-lg border border-border bg-white"
    >
      <text x="24" y="26" fontSize="14" fill="#475569">
        무료 테스트 그림 · 실제 AI 생성 아님
      </text>
      {suggestion.scenes.map((scene, index) => (
        <g key={index} transform={`translate(24, ${index * 100 + 42})`}>
          <rect width="432" height="76" rx="12" fill="#eff6ff" stroke="#93c5fd" />
          <circle cx="28" cy="38" r="17" fill="#1d4ed8" />
          <text x="28" y="44" textAnchor="middle" fontSize="18" fill="white">
            {index + 1}
          </text>
          <text x="58" y="34" fontSize="15" fill="#0f172a">
            장면 {index + 1}
          </text>
          <text x="58" y="57" fontSize="13" fill="#334155">
            {Array.from(scene).slice(0, 25).join('')}
            {Array.from(scene).length > 25 ? '…' : ''}
          </text>
          {index < suggestion.scenes.length - 1 && (
            <path d="M216 80v13m-5-5 5 5 5-5" stroke="#1d4ed8" fill="none" strokeWidth="2" />
          )}
        </g>
      ))}
    </svg>
  )

  return (
    <section aria-label="무료 그림 테스트" className="space-y-3 rounded-lg bg-secondary p-4">
      <p className="text-sm">
        무료 테스트입니다. 장면 구성을 표시하는 테스트 그림이며 실제 AI가 그린 그림이 아닙니다.
        적용은 이 화면의 미리보기에만 반영됩니다. 새로고침하거나 본문을 저장하면 사라집니다.
      </p>
      {disabled ? (
        <p role="status">본문을 저장하고 최신 제안을 확인한 뒤 그림을 테스트해 주세요.</p>
      ) : (
        <>
          {stage === 'idle' && (
            <Button type="button" onClick={() => setStage('generating')}>
              이 내용으로 그림 만들기
            </Button>
          )}
          {stage === 'generating' && <p role="status">테스트 그림을 만들고 있어요…</p>}
          {stage === 'preview' && (
            <>
              <h4 className="font-semibold">그림 미리보기</h4>
              {picture}
              <label className="block text-sm font-medium" htmlFor={`${id}-alt`}>
                그림 대체텍스트
              </label>
              <textarea
                id={`${id}-alt`}
                className="w-full rounded-md border border-border bg-background p-2"
                value={altText}
                maxLength={300}
                onChange={(event) => {
                  setAltText(event.target.value)
                  setReviewed(false)
                }}
              />
              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={reviewed}
                  onChange={(event) => setReviewed(event.target.checked)}
                />
                원문 근거와 그림 구성, 대체텍스트를 확인했습니다.
              </label>
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  disabled={!reviewed || !altText.trim()}
                  onClick={() => setStage('applied')}
                >
                  문서 미리보기에 추가
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setStage('idle')
                    setReviewed(false)
                  }}
                >
                  그림 버리기
                </Button>
              </div>
            </>
          )}
          {stage === 'applied' && (
            <>
              <p role="status">테스트 그림을 문서 미리보기에 추가했습니다.</p>
              <h4 className="font-semibold">문서 적용 미리보기</h4>
              <p className="whitespace-pre-wrap">{excerpt}</p>
              <figure className="space-y-2">
                {picture}
                <figcaption className="text-sm">{altText}</figcaption>
              </figure>
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  setStage('preview')
                  setReviewed(false)
                }}
              >
                문서 미리보기에서 제거
              </Button>
            </>
          )}
        </>
      )}
    </section>
  )
}
