import { useId, useRef, useState, type FormEvent } from 'react'
import { Pencil, Plus } from 'lucide-react'

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
export function WorkspaceMenu() {
  const { workspaces, currentId, select, create, rename } = useWorkspace()
  const ids = useId()
  const selectId = `${ids}-select`
  const titleId = `${ids}-title`
  const descriptionId = `${ids}-description`
  const nameId = `${ids}-name`
  const hintId = `${ids}-hint`
  const errorId = `${ids}-error`

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

  return (
    <div className="workspace-menu relative flex flex-wrap items-center gap-2">
      <label className="text-sm font-semibold text-muted-foreground" htmlFor={selectId}>
        작업 공간
      </label>
      <select
        className="h-9 min-w-44 rounded-[10px] border border-input bg-card px-3 text-sm text-foreground"
        id={selectId}
        value={current?.id ?? ''}
        onChange={(event) => select(event.target.value)}
      >
        {/* 문서 수는 적지 않는다. 목록은 로그인할 때 한 번 읽으므로, 올리거나 지운
            뒤에는 틀린 수가 그대로 남는다 — 틀린 숫자는 없는 숫자보다 나쁘다.
            (서버는 문서 수를 준다: 빈 작업 공간만 지울 수 있다는 판정에 쓰인다.) */}
        {workspaces.map((workspace) => (
          <option key={workspace.id} value={workspace.id}>
            {workspace.name}
          </option>
        ))}
      </select>
      <Button variant="outline" size="sm" type="button" onClick={() => open('create', '')}>
        <Plus className="size-4" aria-hidden="true" />
        새로 만들기
      </Button>
      <Button
        variant="ghost"
        size="sm"
        type="button"
        disabled={current === null}
        onClick={() => {
          if (current === null) {
            return
          }
          // 기존 이름을 채워 둬야 한 글자만 고치는 일이 쉬워진다.
          open('rename', current.name)
        }}
      >
        <Pencil className="size-4" aria-hidden="true" />
        이름 바꾸기
      </Button>

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
