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
  const { container } = render(
    <WorkspaceContext.Provider value={value}>
      <WorkspaceMenu />
    </WorkspaceContext.Provider>,
  )
  return { value, container }
}

/** 대화상자의 이름 입력. 포털로 나가 있어 `container` 밖에 있다. */
function nameInput(): HTMLInputElement {
  return screen.getByLabelText('작업 공간 이름')
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('작업 공간 메뉴', () => {
  it('작업 공간을 모두 보여주고 지금 고른 것을 표시한다', () => {
    renderMenu({ currentId: 'w2' })

    const menu = screen.getByLabelText('작업 공간')
    expect(menu).toHaveValue('w2')
    expect(screen.getByRole('option', { name: '기본 작업 공간' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '민원 안내' })).toBeInTheDocument()
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

describe('작업 공간 대화상자', () => {
  it('열리면 이름 입력으로 초점이 간다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))

    expect(screen.getByRole('dialog')).toHaveAccessibleName('새 작업 공간')
    expect(nameInput()).toHaveFocus()
  })

  it('Esc로 닫히고 초점이 열었던 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    renderMenu()
    const trigger = screen.getByRole('button', { name: '새로 만들기' })

    await user.click(trigger)
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('Tab이 대화상자 밖으로 나가지 않는다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))
    const dialog = screen.getByRole('dialog')

    // 이름 입력 → 취소 → 확인 → 다시 이름 입력. 한 바퀴를 돌고도 밖으로 새지 않는다.
    for (let step = 0; step < 5; step += 1) {
      await user.tab()
      expect(dialog).toContainElement(document.activeElement as HTMLElement)
    }
    // 반대 방향도 마찬가지다.
    for (let step = 0; step < 5; step += 1) {
      await user.tab({ shift: true })
      expect(dialog).toContainElement(document.activeElement as HTMLElement)
    }
  })

  it('열려 있는 동안 뒤 배경을 낭독기에서 감춘다', async () => {
    const user = userEvent.setup()
    const { container } = renderMenu()

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))

    expect(container).toHaveAttribute('aria-hidden', 'true')
    expect(container).toHaveAttribute('inert')
    // 배경이 감춰지면 뒤의 버튼은 접근성 트리에서 사라진다.
    expect(screen.queryByRole('button', { name: '새로 만들기' })).not.toBeInTheDocument()

    await user.keyboard('{Escape}')

    expect(container).not.toHaveAttribute('aria-hidden')
    expect(container).not.toHaveAttribute('inert')
  })
})

describe('작업 공간 만들기', () => {
  it('이름을 받아 만들고 대화상자를 닫는다', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockResolvedValue(undefined)
    renderMenu({ create })

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))
    await user.type(nameInput(), '  복지 안내  ')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    // 공백 정리는 서버가 한다 — 화면은 사용자가 적은 그대로 보낸다.
    expect(create).toHaveBeenCalledWith('  복지 안내  ')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('취소하면 아무것도 만들지 않는다', async () => {
    const user = userEvent.setup()
    const create = vi.fn()
    renderMenu({ create })

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))
    await user.type(nameInput(), '복지 안내')
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(create).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('같은 이름이면 대화상자 안에서 사유를 알리고 닫지 않는다', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockRejectedValue(new ApiError(409, '같은 이름의 작업 공간이 있습니다'))
    renderMenu({ create })

    await user.click(screen.getByRole('button', { name: '새로 만들기' }))
    await user.type(nameInput(), '민원 안내')
    await user.click(screen.getByRole('button', { name: '만들기' }))

    // 고칠 입력이 사라지면 안 된다 — 오류는 그 입력 옆에 남는다.
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('같은 이름의 작업 공간이 있습니다')
    expect(screen.getByRole('dialog')).toContainElement(alert)
    expect(nameInput()).toHaveValue('민원 안내')
    expect(nameInput()).toHaveAttribute('aria-invalid', 'true')
    expect(nameInput()).toHaveAccessibleDescription(/같은 이름의 작업 공간이 있습니다/)
  })
})

describe('작업 공간 이름 바꾸기', () => {
  it('지금 이름을 채운 채 열리고 새 이름으로 바꾼다', async () => {
    const user = userEvent.setup()
    const rename = vi.fn().mockResolvedValue(undefined)
    renderMenu({ currentId: 'w2', rename })

    await user.click(screen.getByRole('button', { name: '이름 바꾸기' }))

    // 기존 이름이 기본값으로 들어가야 한 글자만 고치는 일이 쉬워진다.
    expect(nameInput()).toHaveValue('민원 안내')
    expect(screen.getByRole('dialog')).toHaveTextContent('‘민원 안내’의 새 이름')

    await user.clear(nameInput())
    await user.type(nameInput(), '복지 안내')
    await user.click(screen.getByRole('button', { name: '바꾸기' }))

    expect(rename).toHaveBeenCalledWith('w2', '복지 안내')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('바꾸지 못하면 사유를 알린다', async () => {
    const user = userEvent.setup()
    const rename = vi.fn().mockRejectedValue(new ApiError(422, '작업 공간 이름을 입력해 주세요'))
    renderMenu({ currentId: 'w2', rename })

    await user.click(screen.getByRole('button', { name: '이름 바꾸기' }))
    await user.clear(nameInput())
    await user.type(nameInput(), '   ')
    await user.click(screen.getByRole('button', { name: '바꾸기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('작업 공간 이름을 입력해 주세요')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
