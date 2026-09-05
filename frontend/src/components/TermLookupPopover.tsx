import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'

import { ApiError } from '../api/client'
import { lookupTerm, MAX_TERM_QUERY_CHARS } from '../api/dictionary'
import type {
  DictionaryAttribution,
  DictionaryLookupCandidate,
  TermMatchKind,
  TermStrategy,
} from '../api/types'
import { cn } from '../lib/utils'
import { Badge } from './ui/Badge'
import { Button } from './ui/Button'

/** 선택이 멎은 뒤 조회를 쏘기까지 기다리는 시간. 마우스를 끌며 선택 범위를 넓히는
 * 동안 매 픽셀마다 요청을 보내지 않기 위해서다(계획 §3.5). */
const DEBOUNCE_MS = 250

/** 429에 `Retry-After`가 비어 있을 때 쓰는 안전한 기본 대기 시간(초). */
const DEFAULT_RETRY_SECONDS = 60

const MATCH_KIND_LABEL: Record<TermMatchKind, string> = {
  exact: '정확히 일치',
  inflected: '활용형 일치',
  compound_part: '복합어 일부',
}

const STRATEGY_LABEL: Record<TermStrategy, string> = {
  substitute: '바꿀 수 있음',
  gloss: '설명 추가',
  keep: '그대로 유지',
}

type PopoverStatus = 'loading' | 'success' | 'disabled' | 'rate-limited' | 'network-error'

interface PopoverState {
  target: HTMLTextAreaElement
  /** 팝업을 앵커할 위치(뷰포트 기준). 열릴 때 한 번 잰다. */
  anchor: { top: number; left: number }
  selectionStart: number
  selectionEnd: number
  query: string
  status: PopoverStatus
  candidates: DictionaryLookupCandidate[]
  dictionary: DictionaryAttribution | null
  /** disabled·network-error 상태에서 보여줄 문구. */
  message: string | null
  retryAfterSeconds: number | null
}

export interface TermLookupPopoverProps {
  /**
   * 결과 패널(단위 textarea들 또는 단일 textarea 폴백)을 감싸는 요소. 선택 이벤트를
   * 여기 하나에만 건다 — 이벤트가 거품처럼 올라오므로 안의 textarea가 몇 개든 상관없다.
   */
  containerRef: RefObject<HTMLElement>
  /**
   * 지금 결과 전체 문자열(`ReviewEditor`의 `draft`). 단위별 textarea의 `data-unit-index`를
   * 이 값을 `\n`으로 쪼갠 배열의 색인으로 되짚어 치환 대상을 찾는다.
   */
  value: string
  /** 치환된 전체 문자열을 반영한다(`setDraft`) — 이 호출 하나로 저장 상태도 `dirty`가 된다. */
  onApply: (next: string) => void
  /** 저장·내려받기가 도는 동안은 선택 조회를 걸지 않는다. */
  disabled?: boolean
}

/**
 * 검수 결과 편집기 위에 뜨는 「쉬운 말 후보」 팝업(P0-5 조각 5, 계획 §3.5).
 *
 * textarea는 밑줄을 그을 수 없어 「용어를 클릭하면」을 흉내낼 수 없다 — 그래서 트리거는
 * **선택**이다. 사용자가 결과 textarea 안에서 글자를 선택하면(마우스 끌기·더블클릭·
 * 키보드 선택) 250ms 뒤 그 문자열로 `POST /dictionary/lookup`을 부르고, 근처에 후보
 * 팝업을 띄운다. `strategy === 'substitute'`인 후보(`applicable`)만 치환 버튼을 낸다.
 */
export function TermLookupPopover({
  containerRef,
  value,
  onApply,
  disabled = false,
}: TermLookupPopoverProps) {
  const [state, setState] = useState<PopoverState | null>(null)
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const abortControllerRef = useRef<AbortController | null>(null)
  const countdownIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  /** 422(사전 조회 꺼짐)을 한 번 받으면 이 컴포넌트가 살아 있는 동안 다시 조회하지 않는다. */
  const disabledForSessionRef = useRef(false)
  const disabledMessageRef = useRef<string | null>(null)
  /** 429로 남은 대기 시각(ms epoch). 그 전까지는 새 조회를 걸지 않는다. */
  const rateLimitedUntilRef = useRef<number | null>(null)
  /** 팝업이 닫힌 뒤 focus를 되돌릴 대상과 caret 위치. 렌더 뒤 effect에서 소비한다. */
  const pendingFocusRef = useRef<{ target: HTMLTextAreaElement; caret: number } | null>(null)

  const clearDebounce = useCallback(() => {
    if (debounceTimerRef.current !== null) {
      clearTimeout(debounceTimerRef.current)
      debounceTimerRef.current = null
    }
  }, [])

  const clearCountdown = useCallback(() => {
    if (countdownIntervalRef.current !== null) {
      clearInterval(countdownIntervalRef.current)
      countdownIntervalRef.current = null
    }
  }, [])

  const closePopover = useCallback(
    (refocus: { target: HTMLTextAreaElement; caret: number } | null) => {
      abortControllerRef.current?.abort()
      clearDebounce()
      clearCountdown()
      setState(null)
      if (refocus !== null) {
        pendingFocusRef.current = refocus
      }
    },
    [clearCountdown, clearDebounce],
  )

  // 팝업이 닫힌 뒤(state가 null이 된 렌더 다음) 대기 중인 초점 요청을 소비한다.
  // SegmentedResultEditor의 분할·병합 캐럿 복원과 같은 패턴이다 — DOM이 갱신된 다음
  // 렌더에서만 대상 textarea에 새 값이 반영돼 있으므로 effect에서 돈다.
  useEffect(() => {
    const pending = pendingFocusRef.current
    if (pending === null) {
      return
    }
    pendingFocusRef.current = null
    pending.target.focus()
    pending.target.setSelectionRange(pending.caret, pending.caret)
  })

  const startCountdown = useCallback(() => {
    clearCountdown()
    countdownIntervalRef.current = setInterval(() => {
      setState((prev) => {
        if (prev === null || prev.status !== 'rate-limited' || prev.retryAfterSeconds === null) {
          return prev
        }
        const nextRemaining = prev.retryAfterSeconds - 1
        if (nextRemaining <= 0) {
          clearCountdown()
          rateLimitedUntilRef.current = null
          return { ...prev, retryAfterSeconds: 0 }
        }
        return { ...prev, retryAfterSeconds: nextRemaining }
      })
    }, 1000)
  }, [clearCountdown])

  const performLookup = useCallback(
    async (
      target: HTMLTextAreaElement,
      selectionStart: number,
      selectionEnd: number,
      query: string,
    ) => {
      const anchorRect = target.getBoundingClientRect()
      const anchor = { top: anchorRect.bottom + 8, left: anchorRect.left }

      if (disabledForSessionRef.current) {
        setState({
          target,
          anchor,
          selectionStart,
          selectionEnd,
          query,
          status: 'disabled',
          candidates: [],
          dictionary: null,
          message: disabledMessageRef.current,
          retryAfterSeconds: null,
        })
        return
      }

      const now = Date.now()
      const rateLimitedUntil = rateLimitedUntilRef.current
      if (rateLimitedUntil !== null && now < rateLimitedUntil) {
        const remaining = Math.max(1, Math.ceil((rateLimitedUntil - now) / 1000))
        setState({
          target,
          anchor,
          selectionStart,
          selectionEnd,
          query,
          status: 'rate-limited',
          candidates: [],
          dictionary: null,
          message: null,
          retryAfterSeconds: remaining,
        })
        return
      }

      abortControllerRef.current?.abort()
      const controller = new AbortController()
      abortControllerRef.current = controller

      setState({
        target,
        anchor,
        selectionStart,
        selectionEnd,
        query,
        status: 'loading',
        candidates: [],
        dictionary: null,
        message: null,
        retryAfterSeconds: null,
      })

      try {
        const response = await lookupTerm(query, controller.signal)
        if (controller.signal.aborted) {
          return
        }
        setState((prev) =>
          prev === null
            ? prev
            : {
                ...prev,
                status: 'success',
                candidates: response.candidates,
                dictionary: response.dictionary,
              },
        )
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
        if (error instanceof ApiError) {
          if (error.status === 422) {
            disabledForSessionRef.current = true
            disabledMessageRef.current = error.message
            setState((prev) =>
              prev === null ? prev : { ...prev, status: 'disabled', message: error.message },
            )
            return
          }
          if (error.status === 429) {
            const seconds = error.retryAfterSeconds ?? DEFAULT_RETRY_SECONDS
            rateLimitedUntilRef.current = Date.now() + seconds * 1000
            setState((prev) =>
              prev === null
                ? prev
                : { ...prev, status: 'rate-limited', retryAfterSeconds: seconds },
            )
            startCountdown()
            return
          }
        }
        // 네트워크 오류(ApiError.status === NETWORK_ERROR_STATUS)와 그 밖의 서버 오류
        // (500/503)는 조용한 안내 하나로 묶는다 — 사용자가 선택만 했을 뿐인 부수 조회라
        // alert로 흐름을 끊지 않는다.
        setState((prev) => (prev === null ? prev : { ...prev, status: 'network-error' }))
      }
    },
    [startCountdown],
  )

  const scheduleLookup = useCallback(
    (target: HTMLTextAreaElement) => {
      const start = target.selectionStart ?? 0
      const end = target.selectionEnd ?? 0
      const raw = target.value.slice(start, end)
      const trimmed = raw.trim()
      clearDebounce()

      if (trimmed === '') {
        // 선택이 사라졌다 — 열려 있던 팝업을 정직하게 닫는다(더 이상 무엇에 대한
        // 답인지 가리킬 대상이 없다).
        if (state !== null) {
          closePopover(null)
        }
        return
      }
      if (trimmed.length > MAX_TERM_QUERY_CHARS) {
        // 너무 긴 선택은 조회를 걸지 않는다 — 이미 열려 있는 팝업은 손대지 않는다.
        return
      }

      debounceTimerRef.current = setTimeout(() => {
        void performLookup(target, start, end, trimmed)
      }, DEBOUNCE_MS)
    },
    [clearDebounce, closePopover, performLookup, state],
  )

  useEffect(() => {
    const container = containerRef.current
    if (container === null || disabled) {
      return
    }
    function handleSelectionEvent(event: Event): void {
      const target = event.target
      if (!(target instanceof HTMLTextAreaElement)) {
        return
      }
      scheduleLookup(target)
    }
    container.addEventListener('mouseup', handleSelectionEvent)
    container.addEventListener('keyup', handleSelectionEvent)
    container.addEventListener('dblclick', handleSelectionEvent)
    return () => {
      container.removeEventListener('mouseup', handleSelectionEvent)
      container.removeEventListener('keyup', handleSelectionEvent)
      container.removeEventListener('dblclick', handleSelectionEvent)
    }
  }, [containerRef, disabled, scheduleLookup])

  // Esc는 초점이 어디에 있든(textarea 안이든 팝업 버튼 위든) 닫는다 — 선택이 트리거라
  // 팝업이 뜬 순간에도 초점은 보통 textarea에 그대로 있다.
  useEffect(() => {
    if (state === null) {
      return
    }
    function handleKeyDown(event: KeyboardEvent): void {
      if (event.key !== 'Escape') {
        return
      }
      setState((prev) => {
        if (prev === null) {
          return prev
        }
        closePopover({ target: prev.target, caret: prev.selectionStart })
        return prev
      })
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [state, closePopover])

  // 언마운트 시 타이머·요청을 정리한다.
  useEffect(
    () => () => {
      abortControllerRef.current?.abort()
      clearDebounce()
      clearCountdown()
    },
    [clearDebounce, clearCountdown],
  )

  const handleApply = useCallback(
    (candidate: DictionaryLookupCandidate) => {
      if (state === null) {
        return
      }
      const { target, selectionStart, selectionEnd } = state
      const unitIndexAttr = target.dataset.unitIndex
      if (unitIndexAttr !== undefined) {
        const unitIndex = Number(unitIndexAttr)
        const units = value.split('\n')
        const unitText = units[unitIndex] ?? ''
        units[unitIndex] =
          unitText.slice(0, selectionStart) + candidate.easy_term + unitText.slice(selectionEnd)
        onApply(units.join('\n'))
      } else {
        onApply(value.slice(0, selectionStart) + candidate.easy_term + value.slice(selectionEnd))
      }
      const nextCaret = selectionStart + candidate.easy_term.length
      closePopover({ target, caret: nextCaret })
    },
    [state, value, onApply, closePopover],
  )

  if (state === null) {
    return null
  }

  const { anchor, query, status, candidates, dictionary, message, retryAfterSeconds } = state

  return createPortal(
    <div
      role="dialog"
      aria-label="쉬운 말 후보"
      style={{ position: 'fixed', top: anchor.top, left: anchor.left }}
      className="z-50 w-[min(22rem,90vw)] rounded-[12px] border border-border bg-card p-4 text-card-foreground shadow-lg motion-reduce:transition-none"
    >
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <h2 className="text-sm font-bold text-foreground">쉬운 말 후보</h2>
          <p className="text-xs text-muted-foreground">‘{query}’</p>
        </div>
        <button
          type="button"
          aria-label="닫기"
          className="flex size-11 shrink-0 items-center justify-center rounded-full text-muted-foreground hover:bg-secondary hover:text-foreground"
          onClick={() => closePopover({ target: state.target, caret: state.selectionStart })}
        >
          <X className="size-[18px]" aria-hidden="true" />
        </button>
      </div>

      {status === 'loading' && (
        <p role="status" className="text-sm text-muted-foreground">
          찾는 중…
        </p>
      )}

      {status === 'disabled' && (
        <p role="alert" className="form-error">
          {message}
        </p>
      )}

      {status === 'rate-limited' && (
        <p role="status" className="text-sm text-muted-foreground">
          잠시 후 다시 시도해 주세요. ({retryAfterSeconds}초)
        </p>
      )}

      {status === 'network-error' && (
        <p className="text-sm text-muted-foreground">사전 조회에 실패했습니다.</p>
      )}

      {status === 'success' && (
        <>
          <p role="status" className="mb-2 text-sm text-muted-foreground">
            {candidates.length > 0 ? `후보 ${candidates.length}건` : '사전에 없는 말입니다'}
          </p>
          {candidates.length > 0 && (
            <ul className="flex flex-col gap-3">
              {candidates.map((candidate, index) => (
                <li
                  key={`${candidate.term}-${index}`}
                  className="rounded-[10px] border border-border p-3"
                >
                  <div className="mb-1 flex flex-wrap items-center gap-1.5">
                    <Badge tone="primary" withIcon={false}>
                      {MATCH_KIND_LABEL[candidate.match_kind]}
                    </Badge>
                    <Badge tone="neutral" withIcon={false}>
                      {STRATEGY_LABEL[candidate.strategy]}
                    </Badge>
                  </div>
                  <p className="text-[15px] font-semibold text-foreground">{candidate.easy_term}</p>
                  {candidate.definition !== null && (
                    <p className="mt-1 text-sm text-muted-foreground">{candidate.definition}</p>
                  )}
                  {candidate.caution !== null && (
                    <p
                      className={cn(
                        'mt-1 text-sm',
                        candidate.risk === 'high'
                          ? 'font-semibold text-danger'
                          : 'text-muted-foreground',
                      )}
                    >
                      주의: {candidate.caution}
                    </p>
                  )}
                  {candidate.applicable && (
                    <Button
                      type="button"
                      size="sm"
                      className="mt-2 h-11"
                      onClick={() => handleApply(candidate)}
                    >
                      바꾸기
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {dictionary !== null && (
            <p className="mt-3 text-xs text-muted-foreground">
              {dictionary.name} · {dictionary.license}
            </p>
          )}
        </>
      )}
    </div>,
    document.body,
  )
}
