import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { TermsPage } from './TermsPage'

describe('이용약관 화면', () => {
  it('원본(src/content/legal/terms-of-service.md)의 제목과 버전 문자열을 그린다', () => {
    render(<TermsPage />)

    // TSX에 문구를 옮기지 않았다는 증거 — 원본이 바뀌면 이 값도 같이 바뀐다.
    expect(screen.getByRole('heading', { name: '이용약관', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/terms-\d{4}-\d{2}-\d{2}(-draft)?/)).toBeInTheDocument()
  })

  it('초안 경고 문구를 그대로 보여준다 — 실수로 배포돼도 화면이 스스로 초안임을 말한다', () => {
    render(<TermsPage />)

    expect(screen.getByText(/이 문서는 초안이며 아직 게시하지 않았다/)).toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<TermsPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
