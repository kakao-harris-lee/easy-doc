import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '../api/client'
import { workspaceContext, workspaceItem } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import type { WorkspaceContextValue } from '../workspace/context'
import { WorkspaceMenu } from './WorkspaceMenu'

/** 작업 공간 두 개짜리 목록 — 전환을 볼 수 있는 최소 구성. */
function twoWorkspaces() {
  return [
    workspaceItem({ id: 'w1', name: '기본 작업 공간', document_count: 2 }),
    workspaceItem({ id: 'w2', name: '민원 안내' }),
  ]
}

function renderMenu(overrides: Partial<WorkspaceContextValue> = {}) {
  const value = workspaceContext({ workspaces: twoWorkspaces(), ...overrides })
  render(
    <WorkspaceContext.Provider value={value}>
      <WorkspaceMenu />
    </WorkspaceContext.Provider>,
  )
  return value
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('작업 공간 메뉴', () => {
  it('작업 공간을 문서 수와 함께 보여주고 지금 고른 것을 표시한다', () => {
    renderMenu({ currentId: 'w2' })

    const menu = screen.getByLabelText('작업 공간')
    expect(menu).toHaveValue('w2')
    expect(screen.getByRole('option', { name: '기본 작업 공간 (문서 2개)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '민원 안내 (문서 0개)' })).toBeInTheDocument()
  })

  it('다른 작업 공간을 고르면 전환한다', async () => {
    const user = userEvent.setup()
    const select = vi.fn()
    renderMenu({ select })

    await user.selectOptions(screen.getByLabelText('작업 공간'), 'w2')

    expect(select).toHaveBeenCalledWith('w2')
  })

  it('목록을 아직 못 받았으면 아무것도 그리지 않는다', () => {
    renderMenu({ workspaces: [], currentId: null })

    expect(screen.queryByLabelText('작업 공간')).not.toBeInTheDocument()
  })
})

describe('작업 공간 만들기', () => {
  it('이름을 받아 만든다', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'prompt').mockReturnValue('  복지 안내  ')
    renderMenu({ create })

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))

    // 공백 정리는 서버가 한다 — 화면은 사용자가 적은 그대로 보낸다.
    expect(create).toHaveBeenCalledWith('  복지 안내  ')
  })

  it('취소하면 아무것도 만들지 않는다', async () => {
    const user = userEvent.setup()
    const create = vi.fn()
    vi.spyOn(window, 'prompt').mockReturnValue(null)
    renderMenu({ create })

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))

    expect(create).not.toHaveBeenCalled()
  })

  it('같은 이름이면 사유를 알린다', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockRejectedValue(new ApiError(409, '같은 이름의 작업 공간이 있습니다'))
    vi.spyOn(window, 'prompt').mockReturnValue('민원 안내')
    renderMenu({ create })

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))

    // prompt는 닫히고 나면 흔적이 없다 — 실패는 화면에 남아 있어야 한다.
    expect(await screen.findByRole('alert')).toHaveTextContent('같은 이름의 작업 공간이 있습니다')
  })
})

describe('작업 공간 이름 바꾸기', () => {
  it('지금 이름을 채워 물어보고 바꾼다', async () => {
    const user = userEvent.setup()
    const rename = vi.fn().mockResolvedValue(undefined)
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('복지 안내')
    renderMenu({ currentId: 'w2', rename })

    await user.click(screen.getByRole('button', { name: '이름 바꾸기' }))

    // 기존 이름이 기본값으로 들어가야 한 글자만 고치는 일이 쉬워진다.
    expect(prompt).toHaveBeenCalledWith(expect.stringContaining('새 이름'), '민원 안내')
    expect(rename).toHaveBeenCalledWith('w2', '복지 안내')
  })

  it('바꾸지 못하면 사유를 알린다', async () => {
    const user = userEvent.setup()
    const rename = vi.fn().mockRejectedValue(new ApiError(422, '작업 공간 이름을 입력해 주세요'))
    vi.spyOn(window, 'prompt').mockReturnValue('   ')
    renderMenu({ rename })

    await user.click(screen.getByRole('button', { name: '이름 바꾸기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('작업 공간 이름을 입력해 주세요')
  })
})
