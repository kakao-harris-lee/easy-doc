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
  it('누구를 위한 도구인지와 문서 변환 이미지를 보여 준다', () => {
    renderLanding()

    expect(
      screen.getByRole('heading', {
        name: '주민 안내문을 누구나 읽을 수 있는 쉬운 글로',
        level: 1,
      }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('img', {
        name: '복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(/마무리는 담당자가 합니다/)).toBeInTheDocument()
  })

  it('결과가 초안이라는 사실을 경고 블록으로 알린다', () => {
    renderLanding()

    const notice = screen.getByRole('note')

    expect(notice).toHaveTextContent('결과는 초안입니다.')
    expect(notice).toHaveTextContent('담당자가 확인한 뒤 배포하세요.')
  })

  it('어떤 문서를 넣고 넣지 않는지 칩으로 보여 준다', () => {
    renderLanding()

    expect(
      screen.getByRole('heading', { name: '주민에게 나가는 안내문을 쉬운 글 초안으로' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이런 문서에 맞습니다' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이런 문서는 넣지 마세요' })).toBeInTheDocument()

    expect(screen.getByText('복지 서비스 신청 안내')).toBeInTheDocument()
    expect(screen.getByText('주민 공지문')).toBeInTheDocument()
    expect(screen.getByText('지원금·제도 안내')).toBeInTheDocument()
    expect(screen.getByText('개인정보가 든 민원 서류')).toBeInTheDocument()
    expect(screen.getByText('원문 없이 새로 쓸 글')).toBeInTheDocument()
    expect(screen.getByText('법령 조문 전체')).toBeInTheDocument()

    expect(screen.getByText('원문에 없는 내용은 만들지 않습니다.')).toBeInTheDocument()
  })

  it('상세 기능 목록 대신 하나의 변환 예시만 보여 준다', () => {
    renderLanding()

    expect(screen.getByRole('heading', { name: '뜻은 그대로, 문장은 쉽게' })).toBeInTheDocument()
    expect(screen.getByText(/신청 기한 내에 구비서류를 완비하여/)).toBeInTheDocument()
    expect(screen.getByText(/기간 안에 서류를 모두 챙겨/)).toBeInTheDocument()
    expect(screen.getByText(/담당자가 고칠 수 있습니다/)).toBeInTheDocument()
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

  it('경쟁 제품 이름이나 과장된 약속을 적지 않는다', () => {
    const { container } = renderLanding()

    // 요소 경계로 쪼개진 낱말도 잡으려고 textContent 를 통째로 본다.
    expect(container.textContent).not.toMatch(
      /ChatGPT|Gemini|완벽|자동 완성|한 번에|혁신|스마트|누구나 쉽게/,
    )
  })
})
