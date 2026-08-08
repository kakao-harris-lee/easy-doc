import { useId, useState } from 'react'

import { ApiError } from '../api/client'
import { useWorkspace } from '../workspace/context'

const CREATE_PROMPT = '새 작업 공간의 이름을 입력해 주세요. (50자 이내)'
const RENAME_PROMPT = '작업 공간의 새 이름을 입력해 주세요. (50자 이내)'
const FALLBACK_ERROR_MESSAGE = '작업 공간을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 머리말의 작업 공간 메뉴 — 고르기·만들기·이름 바꾸기.
 *
 * 모달 대신 `<select>`와 `window.prompt`를 쓴다. 이름 하나를 받는 일에 자체 대화상자를
 * 만들면 포커스 가두기·Esc 닫기·낭독기 알림을 전부 우리가 다시 구현해야 하고, 그 구현이
 * 어긋나면 키보드 사용자가 갇힌다(KWCAG). 브라우저 기본 대화상자는 그 동작을 이미 갖고
 * 있다. 목록이 길어지거나 삭제·공유가 붙는 시점에 제대로 된 화면으로 옮긴다.
 *
 * 오류는 사라지지 않는 문단(role="alert")으로 알린다 — prompt는 닫히고 나면 아무 흔적이
 * 남지 않아, 실패를 그 자리에서 보여주지 않으면 사용자는 이름이 바뀐 줄로 안다.
 */
export function WorkspaceMenu() {
  const { workspaces, currentId, select, create, rename } = useWorkspace()
  const selectId = useId()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  // 아직 목록을 못 받았으면 아무것도 그리지 않는다 — 빈 선택 상자는 고를 것이 없다는
  // 사실만 알릴 뿐이고, 목록은 로그인 직후 곧바로 도착한다.
  if (workspaces.length === 0) {
    return null
  }

  const current = workspaces.find((workspace) => workspace.id === currentId) ?? null

  /** prompt로 이름을 받아 작업을 수행한다. 취소(null)면 아무 일도 하지 않는다. */
  async function run(name: string | null, action: (value: string) => Promise<void>): Promise<void> {
    if (name === null) {
      return
    }
    setBusy(true)
    setMessage(null)
    try {
      await action(name)
    } catch (caught) {
      // 백엔드 오류 메시지는 사용자에게 보이려고 만든 한국어 문구다(입력값 미포함).
      // 409(같은 이름)·422(빈 이름·길이)·404(사라진 공간) 모두 여기로 온다.
      setMessage(caught instanceof ApiError ? caught.message : FALLBACK_ERROR_MESSAGE)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="workspace-menu">
      <label htmlFor={selectId}>작업 공간</label>
      <select
        id={selectId}
        value={current?.id ?? ''}
        disabled={busy}
        onChange={(event) => select(event.target.value)}
      >
        {workspaces.map((workspace) => (
          <option key={workspace.id} value={workspace.id}>
            {workspace.name} (문서 {workspace.document_count.toLocaleString('ko-KR')}개)
          </option>
        ))}
      </select>
      <button
        type="button"
        disabled={busy}
        onClick={() => void run(window.prompt(CREATE_PROMPT), create)}
      >
        새로 만들기
      </button>
      <button
        type="button"
        disabled={busy || current === null}
        onClick={() => {
          if (current === null) {
            return
          }
          void run(window.prompt(RENAME_PROMPT, current.name), (name) => rename(current.id, name))
        }}
      >
        이름 바꾸기
      </button>
      {message !== null && (
        <p className="form-error" role="alert">
          {message}
        </p>
      )}
    </div>
  )
}
