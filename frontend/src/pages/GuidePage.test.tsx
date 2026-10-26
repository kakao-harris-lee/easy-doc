import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

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
    const { container } = render(<GuidePage />)
    const text = screen.getByText('DOCX·HWPX → 같은 형식')
    const disclosure = screen.getByText('파일 형식과 다운로드').closest('details')

    expect(text).not.toBeVisible()
    expect(disclosure).toHaveClass('help-disclosure')
    expect(disclosure).not.toHaveAttribute('open')
    expect(container.querySelector('.guide-page > .legal-document > ol')).not.toHaveClass(
      'border',
      'bg-card',
    )

    await user.click(screen.getByText('파일 형식과 다운로드'))

    expect(disclosure).toHaveAttribute('open')
    expect(text).toBeVisible()
    expect(screen.getByText('붙여넣은 글·TXT·PDF → TXT')).toBeVisible()
    expect(screen.getByText('10MB 이하')).toBeVisible()
    expect(screen.getByText(/원본 서식은 .*않습니다/)).toBeVisible()

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
    expect(details).toHaveTextContent('Start')
    expect(details).toHaveTextContent('월 99,000원 · 50크레딧')
    expect(details).toHaveTextContent('2026년 11월 1일')
    expect(details).toHaveTextContent('실제 승인일부터 한 달')
    expect(details).toHaveTextContent('다음 결제가 중단')
    expect(details).toHaveTextContent('이미 결제한 이용 기간은 유지')
    expect(details).toHaveTextContent('고객 지원')
    expect(details).toHaveTextContent(
      'Basic·Pro는 판매하지 않으며 가격·제공량·출시 시점을 약속하지 않습니다.',
    )
    expect(details).not.toHaveTextContent('190,000원')
    expect(details).not.toHaveTextContent('599,000원')
    expect(details).not.toHaveTextContent('200크레딧')
    expect(details).not.toHaveTextContent('1,000크레딧')
    expect(details).not.toHaveTextContent('Basic 결제 준비 중')
    expect(details).not.toHaveTextContent('Pro 결제 준비 중')
    expect(details).toHaveTextContent('문단 재변환도 해당 분량만큼 차감')
    expect(details).toHaveTextContent('월 정기결제')
  })

  it('접힌 본문에도 개인정보 처리와 30일 보관·탈퇴 삭제 안내를 남긴다', () => {
    const { container } = render(<GuidePage />)
    expect(container).toHaveTextContent('값이 가려지지 않고 전송됩니다')
    expect(container).toHaveTextContent('이름·주소 등은 탐지하지 못할 수 있습니다')
    expect(container).toHaveTextContent('30일 후 자동 삭제')
    expect(container).toHaveTextContent('일반적으로 계정과 문서가 즉시 삭제되며 복구할 수 없습니다')
    expect(container).toHaveTextContent('실결제 이력이 있어 법령·운영상 보존이 필요한 거래 증빙은 별도 보관')
    expect(container).toHaveTextContent(
      '필수 증빙이 불완전하거나 수동 검토가 필요하면 해결될 때까지 계정 삭제 완료가 제한될 수 있습니다',
    )
  })
})
