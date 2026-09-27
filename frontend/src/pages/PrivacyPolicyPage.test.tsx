import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { PrivacyPolicyPage } from './PrivacyPolicyPage'

describe('개인정보처리방침 화면', () => {
  it('원본(src/content/legal/privacy-policy.md)의 제목과 버전 문자열을 그린다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByRole('heading', { name: '개인정보처리방침', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/privacy-\d{4}-\d{2}-\d{2}(-draft)?/)).toBeInTheDocument()
  })

  it('게시본임을 보여준다 — 시행일이 남고 초안·빈칸 표기는 사라졌다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByText(/시행일: 2026-10-01/)).toBeInTheDocument()
    expect(screen.queryByText(/이 문서는 초안이며 아직 게시하지 않았다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확인 필요/)).not.toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
