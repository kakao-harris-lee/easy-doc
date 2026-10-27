import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { TermsPage } from './TermsPage'

describe('이용약관 화면', () => {
  it('원본(src/content/legal/terms-of-service.md)의 제목과 버전 문자열을 그린다', () => {
    render(<TermsPage />)

    // TSX에 문구를 옮기지 않았다는 증거 — 원본이 바뀌면 이 값도 같이 바뀐다.
    expect(screen.getByRole('heading', { name: '이용약관', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('terms-2026-11-01')).toBeInTheDocument()
  })

  it('유료 조건의 검토일과 적용일 공고 조건을 보여준다', () => {
    render(<TermsPage />)

    expect(screen.getByText(/검토일: 2026-10-26/)).toBeInTheDocument()
    expect(screen.getByText(/유료 서비스 적용일: 2026년 11월 1일/)).toBeInTheDocument()
    expect(screen.getByText(/Start는 월 99,000원에 50크레딧/)).toBeInTheDocument()
    expect(screen.queryByText(/출시 전 공고로 확정/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확정 전에는 신규 실결제를 받지 않습니다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/이 문서는 초안이며 아직 게시하지 않았다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확인 필요/)).not.toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<TermsPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
