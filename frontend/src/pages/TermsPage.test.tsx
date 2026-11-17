import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { TermsPage } from './TermsPage'

describe('이용약관 화면', () => {
  it('원본(src/content/legal/terms-of-service.md)의 제목과 버전 문자열을 그린다', () => {
    render(<TermsPage />)

    // TSX에 문구를 옮기지 않았다는 증거 — 원본이 바뀌면 이 값도 같이 바뀐다.
    expect(screen.getByRole('heading', { name: '이용약관', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('terms-2026-11-17')).toBeInTheDocument()
  })

  it('유료 조건의 검토일과 적용일 공고 조건을 보여준다', () => {
    render(<TermsPage />)

    expect(screen.getByText(/검토일: 2026-11-17/)).toBeInTheDocument()
    expect(screen.getByText(/시행일: 2026년 11월 17일/)).toBeInTheDocument()
    expect(screen.getByText(/유료 서비스 적용일: 2026년 11월 1일/)).toBeInTheDocument()
    expect(screen.getByText(/Start는 월 99,000원에 50크레딧/)).toBeInTheDocument()
    expect(screen.queryByText(/출시 전 공고로 확정/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확정 전에는 신규 실결제를 받지 않습니다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/이 문서는 초안이며 아직 게시하지 않았다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확인 필요/)).not.toBeInTheDocument()
  })

  it('제9조의 해지·환불 기준을 구체적인 수치로 보여준다', () => {
    render(<TermsPage />)

    // 「고객 지원에서 확인합니다」로 끝나던 자리에 기준이 적혀 있어야 한다(terms-2026-11-17).
    expect(screen.getByText(/결제일로부터 7일 이내/)).toBeInTheDocument()
    expect(screen.getByText(/사용하지 않은 크레딧 ÷ 지급 크레딧 50/)).toBeInTheDocument()
    expect(screen.getByText(/10원 미만은 버립니다/)).toBeInTheDocument()
    expect(screen.getByText(/3영업일 이내에 결제한 카드의 승인을 취소/)).toBeInTheDocument()
    expect(screen.getByText(/구독 갱신 중단」 버튼/)).toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<TermsPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
