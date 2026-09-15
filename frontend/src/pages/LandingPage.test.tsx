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
  it('서비스의 핵심 가치와 문서 변환 이미지를 보여 준다', () => {
    renderLanding()

    expect(
      screen.getByRole('heading', {
        name: '어려운 안내문을 읽히는 문서로 바꾸세요',
        level: 1,
      }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('img', {
        name: '복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(/읽기 쉬운 우리말 초안으로 바꿉니다/)).toBeInTheDocument()
  })

  it('상세 기능 목록 대신 하나의 변환 예시만 보여 준다', () => {
    renderLanding()

    expect(screen.getByRole('heading', { name: '뜻은 그대로, 문장은 쉽게' })).toBeInTheDocument()
    expect(screen.getByText(/신청 기한 내에 구비서류를 완비하여/)).toBeInTheDocument()
    expect(screen.getByText(/기간 안에 서류를 모두 챙겨/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '원문을 넣어요' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '문단별 다시 쓰기' })).not.toBeInTheDocument()
  })

  it('가입과 이용 가이드로 바로 보낸다', () => {
    renderLanding()

    expect(screen.getByRole('link', { name: '가입하고 시작하기' })).toHaveAttribute(
      'href',
      '/signup',
    )
    expect(screen.getByRole('link', { name: '이용 가이드 보기' })).toHaveAttribute('href', '/guide')
  })
})
