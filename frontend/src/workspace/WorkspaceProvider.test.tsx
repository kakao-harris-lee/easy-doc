import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createWorkspace, listWorkspaces } from '../api/client'
import { AuthContext } from '../auth/context'
import type { AuthContextValue, AuthStatus } from '../auth/context'
import { workspaceItem } from '../test/factories'
import { useWorkspace } from './context'
import { WorkspaceProvider } from './WorkspaceProvider'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  listWorkspaces: vi.fn(),
  createWorkspace: vi.fn(),
  renameWorkspace: vi.fn(),
}))

const STORAGE_KEY = 'easydoc.workspace_id'

function authValue(status: AuthStatus): AuthContextValue {
  return {
    status,
    user:
      status === 'authenticated'
        ? { id: 'u1', email: 'owner@example.com', email_verified: true }
        : null,
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signInWithGoogle: () => Promise.resolve(),
    signOut: () => undefined,
    refreshMe: () => Promise.resolve(),
  }
}

/**
 * 고른 작업 공간이 화면에 나타나기를 기다린다.
 *
 * `findByTestId`로 기다리면 안 된다 — `current` 문단은 첫 렌더부터(`없음`으로) 이미
 * 붙어 있어서 그 조회는 첫 동기 검사에서 곧바로 성공하고, **본문이 바뀌기를 기다리지
 * 않는다.** 그러면 단언이 통과하는 근거는 오직 "React가 커밋을 제때 끝냈는가"라는
 * 우연이 된다: 목록 응답은 마이크로태스크로 풀리지만 커밋은 React 스케줄러의
 * `setImmediate`에 실리고, Testing Library는 `setTimeout(…, 0)` 한 칸만 배수한 뒤
 * 돌아온다. 둘의 도착 순서는 Node가 보장하지 않아 러너가 바쁘면 뒤집힌다
 * (CI run 32451895280 실패: `Received: 없음`).
 */
async function expectCurrent(workspaceId: string): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('current')).toHaveTextContent(workspaceId)
  })
}

/** 컨텍스트 값을 그대로 화면에 드러내는 관찰용 컴포넌트. */
function Probe() {
  const { workspaces, currentId, create } = useWorkspace()
  return (
    <div>
      <p data-testid="current">{currentId ?? '없음'}</p>
      <p data-testid="names">{workspaces.map((workspace) => workspace.name).join(',')}</p>
      <button type="button" onClick={() => void create('민원 안내')}>
        만들기
      </button>
    </div>
  )
}

function renderProvider(status: AuthStatus = 'authenticated') {
  return render(
    <AuthContext.Provider value={authValue(status)}>
      <WorkspaceProvider>
        <Probe />
      </WorkspaceProvider>
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  vi.mocked(listWorkspaces).mockReset()
  vi.mocked(createWorkspace).mockReset()
  window.localStorage.clear()
})

describe('작업 공간 상태', () => {
  it('로그인하면 목록을 읽고 기본 작업 공간을 고른다', async () => {
    vi.mocked(listWorkspaces).mockResolvedValue({
      items: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
    })
    renderProvider()

    // 첫 번째가 기본 작업 공간이다(서버가 만든 순서로 준다).
    await expectCurrent('w1')
    expect(screen.getByTestId('names')).toHaveTextContent('기본 작업 공간,민원 안내')
  })

  it('기억해 둔 선택을 되살린다', async () => {
    window.localStorage.setItem(STORAGE_KEY, 'w2')
    vi.mocked(listWorkspaces).mockResolvedValue({
      items: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
    })
    renderProvider()

    await expectCurrent('w2')
  })

  it('기억해 둔 선택이 목록에 없으면 기본 작업 공간으로 되돌아간다', async () => {
    // 다른 계정으로 로그인했거나 그 사이 작업 공간이 사라진 상황.
    window.localStorage.setItem(STORAGE_KEY, 'w-gone')
    vi.mocked(listWorkspaces).mockResolvedValue({ items: [workspaceItem({ id: 'w1' })] })
    renderProvider()

    await expectCurrent('w1')
  })

  it('로그인하지 않았으면 목록을 읽지 않는다', () => {
    renderProvider('anonymous')

    // 비로그인 상태의 호출은 401이 되고, 그 401이 세션 만료로 오인된다.
    expect(vi.mocked(listWorkspaces)).not.toHaveBeenCalled()
    expect(screen.getByTestId('current')).toHaveTextContent('없음')
  })

  it('만들면 새 작업 공간으로 옮겨 가고 그 선택을 기억한다', async () => {
    const user = userEvent.setup()
    vi.mocked(listWorkspaces)
      .mockResolvedValueOnce({ items: [workspaceItem({ id: 'w1' })] })
      .mockResolvedValue({
        items: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
      })
    vi.mocked(createWorkspace).mockResolvedValue({
      id: 'w2',
      name: '민원 안내',
      created_at: '2026-08-08T00:00:00Z',
    })
    renderProvider()
    await screen.findByText('기본 작업 공간')

    await user.click(screen.getByRole('button', { name: '만들기' }))

    // 방금 만든 곳에 바로 올릴 수 있어야 한다.
    await expectCurrent('w2')
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('w2')
  })
})
