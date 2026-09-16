import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Check, ChevronDown, Pencil, Plus } from 'lucide-react'

import { ApiError } from '../api/client'
import { useWorkspace } from '../workspace/context'
import { Button } from './ui/Button'
import { ModalDialog } from './ui/Dialog'

const FALLBACK_ERROR_MESSAGE = '작업 공간을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.'
const NAME_HINT = '이름은 50자 이내로 지어 주세요.'

/** 대화상자가 무엇을 하려고 열렸는지. 닫혀 있으면 null. */
type DialogMode = 'create' | 'rename'

/**
 * 머리말의 작업 공간 메뉴 — 고르기·만들기·이름 바꾸기.
 *
 * 만들기와 이름 바꾸기는 `window.prompt`가 아니라 작은 대화상자로 받는다(§6.7).
 * prompt는 닫히고 나면 흔적이 없어서 409·422를 «고쳐야 할 입력 옆»에 보여줄 수 없고
 * (§9), 실패하면 사용자는 이름이 바뀐 줄로 안다. 대화상자는 제목·설명·이름 입력·취소·
 * 확인만 담는다. 초기 초점·포커스 가두기·Esc·초점 복귀는 `ModalDialog`가 맡는다.
 *
 * `AppLayout`이 이 컴포넌트를 DOM에 두 벌(데스크톱 자리 + 모바일 행) 그린다. 열림 상태와
 * `useId` 모두 인스턴스마다 따로이므로 두 벌이 함께 열리거나 id가 겹치지 않는다.
 */
export function WorkspaceMenu({ align = 'left' }: { align?: 'left' | 'right' }) {
  const { workspaces, currentId, select, create, rename } = useWorkspace()
  const ids = useId()
  const menuId = `${ids}-menu`
  const titleId = `${ids}-title`
  const descriptionId = `${ids}-description`
  const nameId = `${ids}-name`
  const hintId = `${ids}-hint`
  const errorId = `${ids}-error`

  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [expanded, setExpanded] = useState(false)

  useEffect(() => {
    if (!expanded) return
    function dismiss(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setExpanded(false)
      }
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [expanded])

  const nameRef = useRef<HTMLInputElement>(null)
  const [mode, setMode] = useState<DialogMode | null>(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 아직 목록을 못 받았으면 아무것도 그리지 않는다 — 빈 선택 상자는 고를 것이 없다는
  // 사실만 알릴 뿐이고, 목록은 로그인 직후 곧바로 도착한다.
  if (workspaces.length === 0) {
    return null
  }

  const current = workspaces.find((workspace) => workspace.id === currentId) ?? null

  function open(next: DialogMode, initial: string): void {
    setExpanded(false)
    triggerRef.current?.focus()
    setMode(next)
    setName(initial)
    setError(null)
  }

  function close(): void {
    // 보내는 중에는 닫지 않는다 — 결과를 보여줄 자리가 사라진다.
    if (busy) {
      return
    }
    setMode(null)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    if (busy || mode === null) {
      return
    }
    // 확인 버튼은 곧 busy로 잠기므로 미리 입력으로 초점을 옮긴다 — 잠긴 버튼에 초점이
    // 남아 있으면 초점이 body로 튕겨 Esc와 Tab이 대화상자에 닿지 않는다.
    nameRef.current?.focus()
    setBusy(true)
    setError(null)
    try {
      // 공백 정리와 길이 판정은 서버가 한다 — 계약이 «정규화 후» 길이를 재므로
      // 입력에 maxLength를 걸면 제어문자가 섞인 정상 이름을 화면이 잘라 버린다.
      if (mode === 'create') {
        await create(name)
      } else if (current !== null) {
        await rename(current.id, name)
      }
      setMode(null)
    } catch (caught) {
      // 백엔드 오류 메시지는 사용자에게 보이려고 만든 한국어 문구다(입력값 미포함).
      // 409(같은 이름)·422(빈 이름·길이)·404(사라진 공간) 모두 여기로 온다.
      setError(caught instanceof ApiError ? caught.message : FALLBACK_ERROR_MESSAGE)
    } finally {
      setBusy(false)
    }
  }

  const creating = mode === 'create'
  const title = creating ? '새 작업 공간' : '작업 공간 이름 바꾸기'
  const description = creating
    ? '작업 공간은 문서 목록과 새 변환의 범위를 나눕니다. 새 작업 공간을 만들면 그쪽으로 옮겨 갑니다.'
    : `‘${current?.name ?? ''}’의 새 이름을 입력해 주세요. 문서는 그대로 남습니다.`
  const confirmLabel = creating ? '만들기' : '바꾸기'
  const busyLabel = creating ? '만드는 중…' : '바꾸는 중…'

  // 자식 버튼에서 올라오는 Esc를 받아 메뉴를 닫는다. 그룹 자체는 초점 대상이 아니다.
  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      ref={rootRef}
      role="group"
      aria-label="작업 공간 관리"
      className="workspace-menu relative min-w-0 max-w-full"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setExpanded(false)
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && expanded) {
          event.stopPropagation()
          setExpanded(false)
          triggerRef.current?.focus()
        }
      }}
    >
      <Button
        ref={triggerRef}
        className="min-h-11 max-w-full justify-between"
        variant="outline"
        type="button"
        aria-label={'작업 공간: ' + (current?.name ?? '선택해 주세요')}
        aria-expanded={expanded}
        aria-controls={menuId}
        onClick={() => setExpanded(!expanded)}
      >
        <span className="max-w-56 truncate">{current?.name ?? '작업 공간 선택'}</span>
        <ChevronDown
          className={'size-4 shrink-0 transition-transform ' + (expanded ? 'rotate-180' : '')}
          aria-hidden="true"
        />
      </Button>
      <div
        id={menuId}
        hidden={!expanded}
        className={`absolute top-full z-40 mt-2 w-72 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-card p-1.5 text-card-foreground shadow-lg ${
          align === 'right' ? 'right-0' : 'left-0'
        }`}
      >
        <p className="px-3 py-2 text-xs font-semibold text-muted-foreground">작업 공간</p>
        <div className="max-h-64 overflow-y-auto" role="group" aria-label="작업 공간 목록">
          {workspaces.map((workspace) => (
            <button
              key={workspace.id}
              type="button"
              aria-pressed={workspace.id === currentId}
              className="flex min-h-11 w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm hover:bg-secondary aria-pressed:bg-secondary aria-pressed:font-semibold"
              onClick={() => {
                select(workspace.id)
                setExpanded(false)
                triggerRef.current?.focus()
              }}
            >
              <span className="min-w-0 flex-1 break-words">{workspace.name}</span>
              {workspace.id === currentId && (
                <Check className="size-4 shrink-0 text-primary" aria-hidden="true" />
              )}
            </button>
          ))}
        </div>
        <div className="mt-1 border-t border-border pt-1">
          <Button
            className="min-h-11 w-full justify-start"
            variant="ghost"
            type="button"
            onClick={() => open('create', '')}
          >
            <Plus className="size-4" aria-hidden="true" />
            새로 만들기
          </Button>
          <Button
            className="min-h-11 w-full justify-start"
            variant="ghost"
            type="button"
            disabled={current === null}
            onClick={() => {
              if (current !== null) open('rename', current.name)
            }}
          >
            <Pencil className="size-4" aria-hidden="true" />
            이름 바꾸기
          </Button>
        </div>
      </div>

      <ModalDialog
        open={mode !== null}
        onClose={close}
        labelledBy={titleId}
        describedBy={descriptionId}
      >
        <h2 className="m-0 text-xl font-bold text-foreground" id={titleId}>
          {title}
        </h2>
        <p className="mt-2 mb-5 text-sm text-muted-foreground" id={descriptionId}>
          {description}
        </p>
        <form className="field" noValidate onSubmit={(event) => void handleSubmit(event)}>
          <label htmlFor={nameId}>작업 공간 이름</label>
          <input
            ref={nameRef}
            id={nameId}
            type="text"
            value={name}
            // 보내는 중에도 초점을 잃지 않도록 disabled 대신 readOnly로 잠근다.
            readOnly={busy}
            aria-invalid={error !== null}
            aria-describedby={error === null ? hintId : `${hintId} ${errorId}`}
            onChange={(event) => setName(event.target.value)}
            data-dialog-autofocus=""
          />
          <p className="field-hint" id={hintId}>
            {NAME_HINT}
          </p>
          {error !== null && (
            // 오류는 대화상자를 닫지 않고 고칠 입력 바로 아래에 남는다 (§9).
            <p className="field-error" id={errorId} role="alert">
              {error}
            </p>
          )}
          <div className="mt-4 flex justify-end gap-2">
            {/* 터치 대상 44×44px 이상 (§10). */}
            <Button
              className="min-h-11 min-w-11"
              variant="outline"
              size="md"
              type="button"
              disabled={busy}
              onClick={close}
            >
              취소
            </Button>
            <Button className="min-h-11 min-w-11" size="md" type="submit" loading={busy}>
              {busy ? busyLabel : confirmLabel}
            </Button>
          </div>
        </form>
      </ModalDialog>
    </div>
  )
}
