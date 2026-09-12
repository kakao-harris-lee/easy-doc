import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { LandingPage } from './LandingPage'

function renderLanding() {
  return render(
    <MemoryRouter>
      <LandingPage />
    </MemoryRouter>,
  )
}

describe('랜딩 화면', () => {
  it('가치 제안 제목과 세 가지 고민을 보여 준다', () => {
    renderLanding()

    expect(
      screen.getByRole('heading', {
        name: '어려운 공공 안내문을, 누구나 읽는 쉬운 글로',
        level: 1,
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(/정책 안내문을 만들 때마다/)).toBeInTheDocument()
    expect(screen.getByText(/공공기관 웹사이트에 있는 정보가 너무 어려워서/)).toBeInTheDocument()
    expect(screen.getByText(/장애인, 고령자, 어린이 고객을 위한 정보를/)).toBeInTheDocument()
  })

  it('일반 AI 채팅과의 차이를 표로 적는다', () => {
    renderLanding()

    const table = screen.getByRole('table', {
      name: 'ChatGPT·Gemini 같은 일반 AI 채팅과 EASY-DOC AI의 차이',
    })
    expect(table).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'ChatGPT · Gemini' })).toBeInTheDocument()
    expect(
      screen.getByText(/공공 안내문을 쉬운 글로 푸는 규칙이 이미 들어 있습니다/),
    ).toBeInTheDocument()
  })

  it('가입과 이용 가이드로 보낸다', () => {
    renderLanding()

    expect(screen.getAllByRole('link', { name: '가입하고 변환해 보기' })[0]).toHaveAttribute(
      'href',
      '/signup',
    )
    expect(screen.getByRole('link', { name: '이용 가이드 보기' })).toHaveAttribute('href', '/guide')
  })
})
