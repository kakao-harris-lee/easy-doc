import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { PrivacyPolicyPage } from './PrivacyPolicyPage'

describe('개인정보처리방침 화면', () => {
  it('원본(src/content/legal/privacy-policy.md)의 제목과 버전 문자열을 그린다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByRole('heading', { name: '개인정보처리방침', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('privacy-2026-10-06')).toBeInTheDocument()
  })

  it('유료 조건의 검토일과 적용일 공고 조건을 보여준다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByText(/검토일: 2026-10-06/)).toBeInTheDocument()
    expect(screen.queryByText(/이 문서는 초안이며 아직 게시하지 않았다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확인 필요/)).not.toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
