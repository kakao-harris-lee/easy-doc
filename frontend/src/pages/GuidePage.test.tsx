import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { GuidePage } from './GuidePage'

describe('이용 가이드 화면', () => {
  it('원본(src/content/guide/user-guide.md)의 제목과 본문 절을 그린다', () => {
    render(<GuidePage />)

    expect(screen.getByRole('heading', { name: '이용 가이드', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '1. 시작하기 전에' })).toBeInTheDocument()
    expect(screen.getByText(/개인정보가 담긴 문서는 올리지 마세요/)).toBeInTheDocument()
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
