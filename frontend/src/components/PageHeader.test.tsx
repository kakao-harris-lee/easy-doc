import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { PageHeader } from './PageHeader'

const BASE = {
  context: '복지정책팀 · 변환 기록',
  title: '변환한 문서를 확인합니다',
  description: '문서의 변환 상태를 보고, 이어서 검수하거나 삭제할 수 있습니다.',
}

describe('페이지 헤더', () => {
  it('맥락 라벨·제목·설명을 정해진 순서로 보여주고 제목을 h1으로 둔다', () => {
    render(
      <MemoryRouter>
        <PageHeader {...BASE} />
      </MemoryRouter>,
    )

    expect(screen.getByText(BASE.context)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: BASE.title })).toBeInTheDocument()
    expect(screen.getByText(BASE.description)).toBeInTheDocument()
  })

  it('대표 행동은 하나뿐이다', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    const { container } = render(
      <MemoryRouter>
        <PageHeader {...BASE} action={{ label: '새 문서 변환', onClick }} />
      </MemoryRouter>,
    )

    // 헤더 전체에 눌리는 것은 대표 행동 하나뿐이다 — 여기에 행동이 늘어나면
    // "지금 할 일 하나"라는 위계가 무너진다(DESIGN.md §5.3).
    expect(container.querySelectorAll('a, button')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '새 문서 변환' }))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('행동을 여러 개 넘기는 것은 타입이 막는다', () => {
    const invalid = (
      <PageHeader
        {...BASE}
        // @ts-expect-error 대표 행동은 배열이 아니다 — 두 번째 행동은 컴파일에서 막힌다.
        action={[
          { label: '새 문서 변환', to: '/' },
          { label: '내보내기', to: '/export' },
        ]}
      />
    )

    expect(invalid).toBeTruthy()
  })

  it('행동을 넘기지 않으면 아무 행동도 그리지 않는다', () => {
    const { container } = render(
      <MemoryRouter>
        <PageHeader {...BASE} />
      </MemoryRouter>,
    )

    expect(container.querySelectorAll('a, button')).toHaveLength(0)
  })

  it('이동하는 행동은 링크로 그린다', () => {
    render(
      <MemoryRouter>
        <PageHeader {...BASE} action={{ label: '새 문서 변환', to: '/' }} />
      </MemoryRouter>,
    )

    // 새 화면으로 가는 행동은 버튼이 아니라 링크여야 새 탭·주소 복사가 동작한다.
    expect(screen.getByRole('link', { name: '새 문서 변환' })).toHaveAttribute('href', '/')
  })
})
