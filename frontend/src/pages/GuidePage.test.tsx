import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { TARGET_PLANS } from '../content/plans/targetPlans'
import { GuidePage } from './GuidePage'

describe('이용 가이드 화면', () => {
  it('원본(src/content/guide/user-guide.md)의 제목과 본문 절을 그린다', () => {
    render(<GuidePage />)

    expect(screen.getByRole('heading', { name: '이용 가이드', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '세 단계로 이용하세요' })).toBeInTheDocument()
    expect(
      screen.getByText(/결과는 초안입니다.*개인정보가 담긴 문서는 올리지 마세요/),
    ).toBeInTheDocument()
  })

  it('사용 흐름보다 먼저 서비스 정의와 대상 문서를 밝힌다', () => {
    render(<GuidePage />)

    expect(screen.getByText(/마무리는 담당자가 합니다/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '이 서비스는', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('누구를 위해', { exact: true })).toBeInTheDocument()
    expect(screen.getByText('무엇을 넣나', { exact: true })).toBeInTheDocument()
    expect(screen.getByText('무엇이 나오나', { exact: true })).toBeInTheDocument()
    expect(
      screen.getByText('원문에 있는 내용만 쉬운 글로 바꾸는 것이 목표입니다.'),
    ).toBeInTheDocument()
  })

  it('실제 서비스 흐름 그림과 핵심 세 단계를 먼저 보여 준다', () => {
    render(<GuidePage />)

    expect(
      screen.getByRole('img', { name: '어려운 문서가 짧고 읽기 쉬운 문서로 바뀌는 모습' }),
    ).toHaveAttribute('src', '/landing-document-flow.svg')
    expect(screen.getByText('원문 넣기', { exact: true })).toBeInTheDocument()
    expect(screen.getByText('쉬운 글 만들기', { exact: true })).toBeInTheDocument()
    expect(screen.getByText('확인하고 내려받기', { exact: true })).toBeInTheDocument()
  })

  it('무료 체험 5크레딧을 받는 방법을 세 단계 안내보다 먼저 알려 준다', () => {
    render(<GuidePage />)

    const trialHeading = screen.getByRole('heading', { name: '무료로 먼저 써 보세요', level: 2 })
    const stepsHeading = screen.getByRole('heading', { name: '세 단계로 이용하세요', level: 2 })

    expect(trialHeading).toBeInTheDocument()
    expect(screen.getByText(/체험 5크레딧/)).toBeInTheDocument()
    expect(
      screen.getByText(/제공자가 확인한 이메일을 넘겨준 소셜 계정은 이 단계가 없습니다/),
    ).toBeInTheDocument()
    expect(trialHeading.compareDocumentPosition(stepsHeading)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
  })

  it('플랜 표가 실제 가격표(TARGET_PLANS)와 같은 크레딧·요금을 싣는다', () => {
    render(<GuidePage />)

    expect(screen.getByRole('heading', { name: '플랜 이용하기', level: 2 })).toBeInTheDocument()

    for (const plan of TARGET_PLANS) {
      // 플랜 이름은 표 밖(FAQ)에도 나올 수 있어 개수는 세지 않는다.
      expect(screen.getAllByText(plan.name).length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText(plan.monthlyPriceLabel)).toBeInTheDocument()
      expect(
        screen.getByRole('cell', { name: plan.monthlyCredits.toLocaleString('ko-KR') }),
      ).toBeInTheDocument()
    }

    expect(screen.getByText('테스트 결제 가능 · 실제 청구 없음')).toBeInTheDocument()
    expect(screen.getAllByText('결제 준비 중')).toHaveLength(2)
  })

  it('최상위 순서 목록은 3항목 단계 띠 둘뿐이다 — 카드 스타일이 걸리는 규약이다', () => {
    const { container } = render(<GuidePage />)

    const stepLists = Array.from(container.querySelectorAll('.legal-document > ol'))

    expect(stepLists).toHaveLength(2)
    stepLists.forEach((stepList) => {
      expect(stepList.querySelectorAll(':scope > li')).toHaveLength(3)
    })
  })

  it('작성자용 규칙을 화면에 그리지 않는다', () => {
    render(<GuidePage />)

    expect(screen.queryByText(/이 문서를 쓰는 규칙/)).not.toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<GuidePage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
