import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { GuidePage } from './GuidePage'

describe('이용 가이드 화면', () => {
  it('원본(src/content/guide/user-guide.md)의 제목과 본문 절을 그린다', () => {
    render(<GuidePage />)

    expect(screen.getByRole('heading', { name: '이용 가이드', level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '세 단계로 이용하세요' })).toBeInTheDocument()
    expect(screen.getByText(/개인정보가 담긴 문서는 올리지 마세요/)).toBeInTheDocument()
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

  it('작성자용 규칙을 화면에 그리지 않는다', () => {
    render(<GuidePage />)

    expect(screen.queryByText(/이 문서를 쓰는 규칙/)).not.toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<GuidePage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
