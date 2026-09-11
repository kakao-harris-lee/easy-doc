import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { PrivacyPolicyPage } from './PrivacyPolicyPage'

describe('개인정보처리방침 화면', () => {
  it('원본(src/content/legal/privacy-policy.md)의 제목과 버전 문자열을 그린다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByRole('heading', { name: '개인정보처리방침', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/privacy-\d{4}-\d{2}-\d{2}(-draft)?/)).toBeInTheDocument()
  })

  it('초안 경고 문구를 그대로 보여준다 — 실수로 배포돼도 화면이 스스로 초안임을 말한다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByText(/이 문서는 초안이며 아직 게시하지 않았다/)).toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
