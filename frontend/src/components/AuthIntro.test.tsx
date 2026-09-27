import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { DRAFT_NOTICE, SERVICE_DEFINITION } from '../content/identity'
import { AuthIntro } from './AuthIntro'

function renderAuthIntro() {
  return render(
    <MemoryRouter>
      <AuthIntro headingId="intro-heading" summary={SERVICE_DEFINITION} />
    </MemoryRouter>,
  )
}

describe('AuthIntro', () => {
  it('제목으로 서비스 정의 한 문장을 그대로 보여준다', () => {
    renderAuthIntro()

    expect(screen.getByRole('heading', { name: SERVICE_DEFINITION })).toBeInTheDocument()
  })

  it('두 번째 단계로 담당자가 초안을 고치는 일을 적는다', () => {
    renderAuthIntro()

    expect(screen.getByText('2. 초안 확인·수정')).toBeInTheDocument()
    expect(
      screen.getByText('쉬운 글 초안을 원문과 나란히 놓고 담당자가 직접 고칩니다.'),
    ).toBeInTheDocument()
  })

  it('고지의 안내 링크가 이용 가이드로 걸린다', () => {
    renderAuthIntro()

    // 랜딩 hero로 되돌아가는 대신 사용법이 적힌 가이드로 보낸다.
    expect(screen.getByRole('link', { name: '이용 가이드 보기' })).toHaveAttribute('href', '/guide')
  })

  it('초안 고지를 모바일에서도 감추지 않는다', () => {
    renderAuthIntro()

    // 단계 목록과 달리 고지는 한 문장이라 스크롤 비용이 거의 없다 — 결과가 초안이라는
    // 사실은 화면 크기와 무관하게 가입 전에 읽혀야 한다.
    const notice = screen.getByRole('note')

    expect(notice).toHaveTextContent(DRAFT_NOTICE)
    expect(notice).not.toHaveClass('hidden')
  })
})
