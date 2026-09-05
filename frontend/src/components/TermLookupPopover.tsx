import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
} from 'react'
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

/**
 * 팝업 예상 너비(px) — 아래 클래스 `w-[min(22rem,90vw)]`와 같은 값이어야 clamp가 실제
 * 렌더와 어긋나지 않는다(§MEDIUM 리뷰 7). 16px 기준 폰트에서 22rem = 352px.
 */
const POPOVER_WIDTH_PX = 352
/** 후보 목록 길이는 미리 알 수 없어(응답 전) 넉넉히 잡은 예상 높이(px). */
const POPOVER_ESTIMATED_HEIGHT_PX = 260
/** 팝업과 화면 가장자리 사이에 남길 최소 여백(px). */
const VIEWPORT_MARGIN_PX = 8

/**
 * 앵커 사각형을 기준으로 뷰포트 안에 들어오는 좌표를 계산한다(§MEDIUM 리뷰 7).
 *
 * 좁은 화면(320px 등)에서 선택 지점이 오른쪽 가장자리에 가까우면 팝업이 화면 밖으로
 * 잘려 나가던 문제를 고친다. 아래로 놓을 자리가 없으면 앵커 위로 뒤집는다.
 */
function clampToViewport(anchorRect: DOMRect): { top: number; left: number } {
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const width = Math.min(POPOVER_WIDTH_PX, viewportWidth * 0.9)
  const maxLeft = Math.max(VIEWPORT_MARGIN_PX, viewportWidth - width - VIEWPORT_MARGIN_PX)
  const left = Math.min(Math.max(anchorRect.left, VIEWPORT_MARGIN_PX), maxLeft)

  const belowTop = anchorRect.bottom + 8
  const fitsBelow = belowTop + POPOVER_ESTIMATED_HEIGHT_PX <= viewportHeight
  const top = fitsBelow
    ? belowTop
    : Math.max(VIEWPORT_MARGIN_PX, anchorRect.top - POPOVER_ESTIMATED_HEIGHT_PX - 8)

  return { top, left }
}

/** Tab 순서에 들어오는 요소들(§11 초점 가두기). `Dialog.tsx`의 선택자와 같다. */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

function focusableWithin(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
}

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
  /**
   * 적용 직전 검증에서 대상 범위가 이미 바뀐 것을 발견했을 때의 짧은 안내(§MEDIUM 리뷰 6).
   * 새 조회를 걸 때마다 `null`로 되돌아간다.
   */
  applyError: string | null
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
  /**
   * 원문(읽기 전용) 패널에 붙일 때 켠다(계획 §3.5 "원문 패널에서도 조회는 되고 적용
   * 버튼은 없다"). 조회는 그대로 동작하지만 `바꾸기` 버튼은 그리지 않는다.
   */
  applyDisabled?: boolean
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
  applyDisabled = false,
}: TermLookupPopoverProps) {
  const [state, setState] = useState<PopoverState | null>(null)
  const isOpen = state !== null
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
  /** 다이얼로그 패널 — 초기 초점·Tab 가두기(§11)가 여기 안에서만 돈다. */
  const panelRef = useRef<HTMLDivElement>(null)
  /** 마지막으로 본 단위 수(줄 수). 바뀌면 열려 있는 팝업을 닫는다(§MEDIUM 리뷰 6). */
  const previousUnitCountRef = useRef<number | null>(null)

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
      const anchor = clampToViewport(anchorRect)

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
          applyError: null,
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
          applyError: null,
        })
        // 팝업을 닫았다가(§MEDIUM 리뷰 4) 대기 시간 안에 다시 선택하면 이 캐시된 분기를
        // 다시 타는데, `closePopover`가 이전 카운트다운 인터벌을 지웠으므로 여기서 다시
        // 걸지 않으면 숫자가 멈춰 보인다.
        startCountdown()
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
        applyError: null,
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
      // 선택 앞뒤 공백은 조회 문자열에서도 치환 범위에서도 뺀다(§MEDIUM 리뷰 5) — 오프셋을
      // 한 번만 계산해 조회와 적용이 같은 [trimmedStart, trimmedEnd)를 보게 한다. 그러지
      // 않으면 조회는 다듬어진 낱말로 하면서 적용은 원래 선택(공백 포함) 범위를 지운다.
      const leadingWs = raw.length - raw.trimStart().length
      const trailingWs = raw.length - raw.trimEnd().length
      const trimmedStart = start + leadingWs
      const trimmedEnd = end - trailingWs
      const trimmed = target.value.slice(trimmedStart, trimmedEnd)
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
        void performLookup(target, trimmedStart, trimmedEnd, trimmed)
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

  // 다이얼로그 바깥을 누르면 닫는다(§LOW 리뷰 8) — 패널 안이나 트리거가 된 컨테이너
  // (선택 중인 textarea) 안을 누른 것은 여기서 다루지 않는다. 컨테이너 안 클릭은 이미
  // `mouseup` 핸들러가 선택 변화를 스스로 판단한다.
  useEffect(() => {
    if (state === null) {
      return
    }
    function handlePointerDown(event: MouseEvent): void {
      const target = event.target
      if (!(target instanceof Node)) {
        return
      }
      const panel = panelRef.current
      if (panel !== null && panel.contains(target)) {
        return
      }
      const container = containerRef.current
      if (container !== null && container.contains(target)) {
        return
      }
      closePopover(null)
    }
    document.addEventListener('mousedown', handlePointerDown)
    return () => document.removeEventListener('mousedown', handlePointerDown)
  }, [state, closePopover, containerRef])

  // 초점 가두기(§11) — 열리는 순간(닫힘→열림 전이) 다이얼로그 안 첫 요소(닫기 버튼)로
  // 초점을 옮긴다. 상태 갱신(loading→success 등)마다 다시 뺏지 않도록 `isOpen`이 실제로
  // 바뀔 때만 돈다.
  useEffect(() => {
    if (!isOpen) {
      return
    }
    const panel = panelRef.current
    if (panel === null) {
      return
    }
    const target = focusableWithin(panel)[0]
    target?.focus()
  }, [isOpen])

  // 저장·내려받기 같은 busy 상태가 되면 팝업을 닫는다(§MEDIUM 리뷰 3) — busy 동안에는
  // 결과 textarea도 잠기므로 열린 채로 두면 갱신되지 않는 대상을 가리키게 된다.
  useEffect(() => {
    if (disabled && state !== null) {
      closePopover(null)
    }
  }, [disabled, state, closePopover])

  // 단위 수(줄 수)가 바뀌면 팝업을 닫는다(§MEDIUM 리뷰 6) — 분할·병합으로 색인이 밀리면
  // 열려 있던 팝업이 다른 단위를 가리키게 된다.
  useEffect(() => {
    const count = value.split('\n').length
    const previous = previousUnitCountRef.current
    previousUnitCountRef.current = count
    if (previous !== null && previous !== count && state !== null) {
      closePopover(null)
    }
  }, [value, state, closePopover])

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
      const { target, selectionStart, selectionEnd, query } = state
      // 단위 수는 그대로여도 그 사이 대상 범위의 글자 자체가 바뀌었을 수 있다(§MEDIUM
      // 리뷰 6) — 적용 직전에 한 번 더 확인해, 다른 곳이 됐을지 모르는 자리에 조용히
      // 엉뚱한 낱말을 끼워 넣지 않는다.
      const current = target.value.slice(selectionStart, selectionEnd)
      if (current !== query) {
        setState((prev) =>
          prev === null
            ? prev
            : {
                ...prev,
                applyError: '선택한 내용이 그새 바뀌어 적용하지 못했습니다. 다시 선택해 주세요.',
              },
        )
        return
      }
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

  /**
   * Tab 가두기(§11) — Esc는 문서 레벨 리스너(위)가 이미 처리하므로 여기서는 Tab만
   * 다룬다. `ModalDialog`(`Dialog.tsx`)의 같은 로직을 이 좁은 다이얼로그에 맞게 되풀이한다.
   */
  function handlePanelKeyDown(event: ReactKeyboardEvent<HTMLDivElement>): void {
    if (event.key !== 'Tab') {
      return
    }
    const panel = panelRef.current
    if (panel === null) {
      return
    }
    const items = focusableWithin(panel)
    const first = items.at(0)
    const last = items.at(-1)
    if (first === undefined || last === undefined) {
      event.preventDefault()
      return
    }
    const active = document.activeElement
    const inside = active instanceof Node && panel.contains(active)
    if (event.shiftKey) {
      if (!inside || active === first) {
        event.preventDefault()
        last.focus()
      }
      return
    }
    if (!inside || active === last) {
      event.preventDefault()
      first.focus()
    }
  }

  if (state === null) {
    return null
  }

  const { anchor, query, status, candidates, dictionary, message, retryAfterSeconds, applyError } =
    state

  return createPortal(
    // 초점은 항상 이 패널 안에 있으므로 keydown이 여기까지 올라온다(Dialog.tsx의
    // ModalDialog와 같은 근거).
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={panelRef}
      role="dialog"
      aria-modal="true"
      aria-label="쉬운 말 후보"
      style={{ position: 'fixed', top: anchor.top, left: anchor.left }}
      className="z-50 w-[min(22rem,90vw)] rounded-[12px] border border-border bg-card p-4 text-card-foreground shadow-lg motion-reduce:transition-none"
      onKeyDown={handlePanelKeyDown}
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
                  {/* 원문(읽기 전용) 패널에서는 적용 버튼을 아예 그리지 않는다(계획 §3.5,
                      HIGH 리뷰 1) — busy(§MEDIUM 리뷰 3) 동안에도 마찬가지다. 두 조건이
                      모두 참일 때만 버튼이 뜬다. */}
                  {candidate.applicable && !applyDisabled && !disabled && (
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

      {/* 적용 직전 검증(§MEDIUM 리뷰 6)이 대상이 바뀐 것을 발견했을 때만 보인다. */}
      {applyError !== null && (
        <p role="alert" className="form-error mt-2">
          {applyError}
        </p>
      )}
    </div>,
    document.body,
  )
}
