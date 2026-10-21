import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { COMPANY_INFO } from '../config/company'
import { DRAFT_NOTICE, SERVICE_DEFINITION } from '../content/identity'
import { TARGET_PLANS } from '../content/plans/targetPlans'
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
        name: '어려운 글을 누구나 읽기 쉬운 글로',
        level: 1,
      }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('img', {
        name: '복잡한 문서가 짧고 읽기 쉬운 문장으로 바뀌는 모습',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(SERVICE_DEFINITION)).toBeInTheDocument()
  })

  it('결과가 초안이라는 사실을 경고 블록으로 알린다', () => {
    renderLanding()

    const notice = screen.getByRole('note')

    expect(notice).toHaveTextContent(DRAFT_NOTICE)
  })

  it('어떤 문서를 넣고 넣지 않는지 칩으로 보여 준다', () => {
    renderLanding()

    expect(screen.getByRole('heading', { name: '일상부터 학습과 업무까지' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이런 문서에 맞습니다' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이런 문서는 넣지 마세요' })).toBeInTheDocument()

    expect(screen.getByText('학교 가정통신문')).toBeInTheDocument()
    expect(screen.getByText('서비스 이용 안내')).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: '일상부터 학습과 업무까지' })).getByText(
        '제품 설명',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('개인정보가 담긴 글')).toBeInTheDocument()
    expect(screen.queryByText('원문 없이 새로 쓸 글')).not.toBeInTheDocument()
    expect(screen.getByText('외부에 공개하지 않는 내부 문서')).toBeInTheDocument()

    expect(
      screen.getByText('원문에 있는 내용만 쉬운 글로 바꾸는 것이 목표입니다.'),
    ).toBeInTheDocument()
  })

  it('변환 예시와 문서 작업 흐름을 보여 준다', () => {
    renderLanding()

    expect(screen.getByRole('heading', { name: '뜻은 그대로, 문장은 쉽게' })).toBeInTheDocument()
    expect(screen.getByText(/행사 참여를 희망하는 경우/)).toBeInTheDocument()
    expect(screen.getByText(/행사에 참여하려면 신청 기간 안에/)).toBeInTheDocument()
    expect(screen.getByText(/결과는 직접 확인하고 고칠 수 있습니다/)).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: '쉬운 글 만들기부터 문서로 저장하기까지' }),
    ).toBeInTheDocument()
  })

  it('가입과 이용 가이드로 바로 보낸다', () => {
    renderLanding()

    expect(screen.getByRole('link', { name: '무료로 체험하기' })).toHaveAttribute('href', '/signup')
    expect(screen.getByRole('link', { name: '이용 가이드 보기' })).toHaveAttribute('href', '/guide')
  })

  it('공개 Start 상품 안내가 실제 가격표와 이용 조건을 보여 준다', () => {
    renderLanding()

    const startPlan = TARGET_PLANS.find((plan) => plan.id === 'start')
    expect(startPlan).toBeDefined()
    if (startPlan === undefined) return

    const card = screen.getByRole('region', { name: 'Start 월 정기결제' })
    expect(card).toHaveTextContent(startPlan.monthlyPriceLabel)
    expect(card).toHaveTextContent(`월 ${startPlan.monthlyCredits.toLocaleString('ko-KR')}크레딧`)
    startPlan.features.forEach((feature) => {
      expect(card).toHaveTextContent(feature.label)
    })
    expect(card).toHaveTextContent('결제 승인일부터 한 달')
    expect(card).toHaveTextContent('매월 자동 결제')
    expect(card).toHaveTextContent('사용량 화면에서 구독 갱신 중단')
    expect(screen.getByRole('link', { name: COMPANY_INFO.supportEmail })).toHaveAttribute(
      'href',
      `mailto:${COMPANY_INFO.supportEmail}`,
    )
    expect(screen.getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms')
    expect(screen.getByRole('link', { name: 'Start 구독 안내' })).toHaveAttribute('href', '/usage')
  })

  it('경쟁 제품 이름이나 과장된 약속을 적지 않는다', () => {
    const { container } = renderLanding()

    // 요소 경계로 쪼개진 낱말도 잡으려고 textContent 를 통째로 본다.
    expect(container.textContent).not.toMatch(
      /ChatGPT|Gemini|완벽|자동 완성|한 번에|혁신|스마트|누구나 쉽게/,
    )
  })
})
