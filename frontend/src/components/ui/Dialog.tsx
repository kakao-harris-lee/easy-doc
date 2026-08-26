import { useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/** Tab 순서에 들어오는 요소들. `disabled`와 `tabindex="-1"`은 뺀다. */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

/**
 * 처음 초점을 받을 요소의 표시 (§11 «초기 초점»). 내용 쪽에서는 `data-dialog-autofocus`
 * 속성을 그대로 적는다 — 컴포넌트 파일이 상수까지 내보내면 fast refresh가 끊긴다.
 */
const AUTOFOCUS_ATTRIBUTE = 'data-dialog-autofocus'

function focusableWithin(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
}

export interface ModalDialogProps {
  /** 열려 있는가. false면 아무것도 그리지 않는다 (내용은 매번 새로 마운트된다). */
  open: boolean
  /** Esc나 취소로 닫으라는 요청. 실제로 닫는 것은 부모의 상태다. */
  onClose: () => void
  /** 제목 요소의 id — `aria-labelledby`로 잇는다. */
  labelledBy: string
  /** 설명 문단의 id — `aria-describedby`로 잇는다. */
  describedBy?: string
  children: ReactNode
}

/**
 * 작은 모달 대화상자 — §11이 요구하는 초기 초점·포커스 가두기·Esc 닫기·초점 복귀를 담당한다.
 *
 * `<dialog showModal()>`을 쓰지 않았다. 브라우저가 그 네 가지를 대신 해 주지만 jsdom
 * 29.1.1에는 `HTMLDialogElement.showModal`·`close`가 **아예 없어서**(`typeof` 확인 결과
 * `undefined`) 단위 테스트에서 열리지조차 않는다. 폴리필은 새 의존성이고, 테스트만
 * 건너뛰면 초점 동작을 아무도 검증하지 못한 채 배포된다 — 그래서 직접 구현하고 그 동작을
 * 테스트로 고정했다.
 *
 * 배경은 `document.body`의 형제 요소에 `inert`와 `aria-hidden`을 **둘 다** 건다. `inert`는
 * 브라우저에서 초점·포인터·낭독기를 한 번에 막는 정본이지만 React 18은 이것을 알려진
 * 프로퍼티로 다루지 않고 jsdom도 동작을 흉내내지 않으므로, 낭독기만이라도 확실히 막도록
 * `aria-hidden`을 같이 건다. 대화상자 자신은 body 바로 아래로 포털해서 그 대상에서 뺀다.
 *
 * 배경을 눌러도 닫히지 않는다 — 적다 만 이름이 오조작 한 번에 사라지지 않게 한다.
 */
export function ModalDialog({
  open,
  onClose,
  labelledBy,
  describedBy,
  children,
}: ModalDialogProps) {
  const overlayRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    const overlay = overlayRef.current
    const panel = panelRef.current
    if (overlay === null || panel === null) {
      return
    }

    // 열기 직전에 초점을 갖고 있던 요소 = 이 대화상자를 연 트리거. 닫힐 때 여기로 돌린다.
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null

    const covered = Array.from(document.body.children).filter((element) => element !== overlay)
    const previous = covered.map((element) => ({
      element,
      ariaHidden: element.getAttribute('aria-hidden'),
      inert: element.getAttribute('inert'),
    }))
    for (const element of covered) {
      element.setAttribute('aria-hidden', 'true')
      element.setAttribute('inert', '')
    }

    const target =
      panel.querySelector<HTMLElement>(`[${AUTOFOCUS_ATTRIBUTE}]`) ?? focusableWithin(panel)[0]
    target?.focus()

    return () => {
      for (const entry of previous) {
        if (entry.ariaHidden === null) {
          entry.element.removeAttribute('aria-hidden')
        } else {
          entry.element.setAttribute('aria-hidden', entry.ariaHidden)
        }
        if (entry.inert === null) {
          entry.element.removeAttribute('inert')
        } else {
          entry.element.setAttribute('inert', entry.inert)
        }
      }
      // 배경을 다시 살린 **뒤에** 초점을 돌린다 — inert 안의 요소는 초점을 받지 못한다.
      trigger?.focus()
    }
  }, [open])

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    if (event.key === 'Escape') {
      // 뒤 화면의 Esc 처리(메뉴 닫기 등)까지 같이 반응하지 않게 여기서 멈춘다.
      event.stopPropagation()
      onClose()
      return
    }
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
      // 초점을 받을 것이 하나도 없으면 Tab은 갈 곳이 없다 — 밖으로 새게 두지 않는다.
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

  if (!open) {
    return null
  }

  return createPortal(
    <div
      ref={overlayRef}
      className="fixed inset-0 z-50 flex items-center justify-center bg-foreground/40 p-4"
    >
      {/* 초점은 항상 이 패널 안에 있으므로 keydown이 여기까지 올라온다. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-describedby={describedBy}
        className="w-full max-w-md rounded-2xl border border-border bg-card p-6 text-card-foreground shadow-lg"
        onKeyDown={handleKeyDown}
      >
        {children}
      </div>
    </div>,
    document.body,
  )
}
