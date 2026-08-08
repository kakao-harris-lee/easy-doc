import { render, screen } from '@testing-library/react'
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
    user: status === 'authenticated' ? { id: 'u1', email: 'owner@example.com' } : null,
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signOut: () => undefined,
  }
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
    expect(await screen.findByTestId('current')).toHaveTextContent('w1')
    expect(screen.getByTestId('names')).toHaveTextContent('기본 작업 공간,민원 안내')
  })

  it('기억해 둔 선택을 되살린다', async () => {
    window.localStorage.setItem(STORAGE_KEY, 'w2')
    vi.mocked(listWorkspaces).mockResolvedValue({
      items: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
    })
    renderProvider()

    expect(await screen.findByTestId('current')).toHaveTextContent('w2')
  })

  it('기억해 둔 선택이 목록에 없으면 기본 작업 공간으로 되돌아간다', async () => {
    // 다른 계정으로 로그인했거나 그 사이 작업 공간이 사라진 상황.
    window.localStorage.setItem(STORAGE_KEY, 'w-gone')
    vi.mocked(listWorkspaces).mockResolvedValue({ items: [workspaceItem({ id: 'w1' })] })
    renderProvider()

    expect(await screen.findByTestId('current')).toHaveTextContent('w1')
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
    expect(await screen.findByTestId('current')).toHaveTextContent('w2')
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('w2')
  })
})
