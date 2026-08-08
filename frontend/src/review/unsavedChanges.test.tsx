/**
 * 저장하지 않은 검수 수정을 지키는지 화면 단위로 본다.
 *
 * 가드는 에디터(변경이 생기는 곳)와 머리말 메뉴(떠나는 곳)에 걸쳐 있어, 둘을 함께
 * 띄우지 않으면 실제로 이동이 막히는지 확인할 수 없다.
 */

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe } from '../api/auth'
import { getConversion, listDocuments } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { AppRoutes } from '../routes/AppRoutes'
import { conversion } from '../test/factories'
import { setUnsavedChanges } from './unsavedChanges'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  signup: vi.fn(),
  fetchMe: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getConversion: vi.fn(),
  listDocuments: vi.fn(),
  saveReview: vi.fn(),
}))

/** 로그인 상태로 검수 화면을 띄운다. */
function renderEditor() {
  return render(
    <AuthProvider>
      {/* 머리말의 작업 공간 메뉴가 이 컨텍스트를 읽는다 — 여기서는 관심사가 아니라
          고정된 값을 꽂는다(요청은 WorkspaceProvider 테스트가 본다). */}
      <WorkspaceContext.Provider value={workspaceContext()}>
        <MemoryRouter initialEntries={['/conversions/c1']}>
          <AppLayout>
            <AppRoutes />
          </AppLayout>
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthProvider>,
  )
}

beforeEach(() => {
  window.localStorage.setItem('easydoc.access_token', 'valid-token')
  vi.mocked(fetchMe).mockResolvedValue({ id: 'u1', email: 'user@example.com' })
  vi.mocked(getConversion).mockResolvedValue(conversion({ easy_text: '초안입니다.' }))
  vi.mocked(listDocuments).mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
})

afterEach(() => {
  window.localStorage.clear()
  setUnsavedChanges(false)
  vi.restoreAllMocks()
})

describe('저장하지 않은 검수 수정', () => {
  it('수정 중에 다른 화면으로 가려 하면 물어보고, 취소하면 그대로 머문다', async () => {
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderEditor()

    await user.type(await screen.findByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('link', { name: '변환 기록' }))

    expect(confirm).toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: '쉬운 글 검수' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '변환 기록' })).not.toBeInTheDocument()
  })

  it('떠나겠다고 하면 이동한다', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderEditor()

    await user.type(await screen.findByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('link', { name: '변환 기록' }))

    expect(await screen.findByRole('heading', { name: '변환 기록' })).toBeInTheDocument()
  })

  it('수정하지 않았으면 묻지 않는다', async () => {
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderEditor()

    await screen.findByRole('heading', { name: '쉬운 글 검수' })
    await user.click(screen.getByRole('link', { name: '변환 기록' }))

    expect(confirm).not.toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: '변환 기록' })).toBeInTheDocument()
  })
})
