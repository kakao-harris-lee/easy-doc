import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { TARGET_PLANS } from '../content/plans/targetPlans'
import { GuidePage } from './GuidePage'

describe('간결한 이용 가이드', () => {
  it('사용 순서와 핵심 고지를 먼저 보여주고 상세 도움말은 접는다', () => {
    const { container } = render(<GuidePage />)
    expect(screen.getByRole('heading', { name: '이용 가이드', level: 1 })).toBeVisible()
    expect(screen.getByText('원문 넣기')).toBeVisible()
    expect(screen.getByText('확인·저장')).toBeVisible()
    expect(
      screen.getByText(/AI 결과는 초안입니다.*개인정보·비공개 문서는 올리지 마세요/),
    ).toBeVisible()
    expect(container.querySelectorAll('details')).toHaveLength(6)
    expect(container.querySelectorAll('details[open]')).toHaveLength(0)
    expect(screen.getByRole('link', { name: '시작하기' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: '예시 보기' })).toHaveAttribute('href', '/examples')
  })

  it('파일 도움말을 펼치면 기존 다운로드 형식과 제약을 확인할 수 있다', async () => {
    const user = userEvent.setup()
    render(<GuidePage />)
    const text = screen.getByText('DOCX·HWPX → 같은 형식')
    expect(text).not.toBeVisible()
    await user.click(screen.getByText('파일 형식과 다운로드'))
    expect(text).toBeVisible()
    expect(screen.getByText('붙여넣은 글·TXT·PDF → TXT')).toBeVisible()
    expect(screen.getByText('10MB 이하')).toBeVisible()
    expect(screen.getByText(/원본 서식은 보존되지 않습니다/)).toBeVisible()
    await user.click(screen.getByText('파일 형식과 다운로드'))
    expect(text).not.toBeVisible()
  })

  it('체험 조건과 변환 시 크레딧 사용을 짧게 안내한다', async () => {
    const user = userEvent.setup()
    render(<GuidePage />)
    await user.click(screen.getByText('무료 체험'))
    expect(screen.getByText('가입·휴대폰 인증 후 5크레딧')).toBeVisible()
    expect(screen.getByText(/번호당 1회 지급 · 지급일부터 한 달간 사용/)).toBeVisible()
    expect(screen.getByText(/불러오기는 무료, 변환은 크레딧을 사용합니다/)).toBeVisible()
  })

  it('요금 도움말에 실제 가격과 재변환·갱신 조건을 보존한다', async () => {
    const user = userEvent.setup()
    const { container } = render(<GuidePage />)
    await user.click(screen.getByText('크레딧과 요금'))
    const details = container.querySelector('details[open]')
    for (const plan of TARGET_PLANS) {
      expect(details).toHaveTextContent(plan.name)
      expect(details).toHaveTextContent(plan.monthlyPriceLabel)
      expect(details).toHaveTextContent(`${plan.monthlyCredits.toLocaleString('ko-KR')}크레딧`)
    }
    expect(details).toHaveTextContent('문단 재변환도 해당 분량만큼 차감')
    expect(details).toHaveTextContent('월 정기결제')
    expect(details).toHaveTextContent('다음 갱신을 중단')
    expect(details).toHaveTextContent('결제 준비 중')
  })

  it('접힌 본문에도 개인정보 처리와 30일 보관·탈퇴 삭제 안내를 남긴다', () => {
    const { container } = render(<GuidePage />)
    expect(container).toHaveTextContent('값이 가려지지 않고 전송됩니다')
    expect(container).toHaveTextContent('이름·주소 등은 탐지하지 못할 수 있습니다')
    expect(container).toHaveTextContent('30일 후 자동 삭제')
    expect(container).toHaveTextContent('계정과 문서가 즉시 삭제되며 복구할 수 없습니다')
  })
})
