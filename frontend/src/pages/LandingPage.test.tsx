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
  it('서비스가 제공하는 결과와 문서 변환 이미지를 보여 준다', () => {
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
    expect(screen.getByText(/원문과 비교해 고친 뒤 문서로 내려받을 수 있어요/)).toBeInTheDocument()
  })

  it('문서 변환 과정을 세 단계로 안내한다', () => {
    renderLanding()

    expect(screen.getByRole('heading', { name: '문서를 올려요' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '쉬운 글로 바꿔요' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '확인하고 내려받아요' })).toBeInTheDocument()
  })

  it('실제 변환 예시와 검수 기능을 짧게 보여 준다', () => {
    renderLanding()

    expect(screen.getByText(/신청 기한 내에 구비서류를 완비하여/)).toBeInTheDocument()
    expect(screen.getByText(/기간 안에 서류를 모두 챙겨/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '사실관계 확인' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '문단별 다시 쓰기' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '쉬운 낱말 찾기' })).toBeInTheDocument()
  })

  it('가입과 이용 가이드로 보낸다', () => {
    renderLanding()

    expect(screen.getByRole('link', { name: '무료로 변환 시작하기' })).toHaveAttribute(
      'href',
      '/signup',
    )
    expect(screen.getByRole('link', { name: '자세한 이용 가이드 보기' })).toHaveAttribute(
      'href',
      '/guide',
    )
  })
})
