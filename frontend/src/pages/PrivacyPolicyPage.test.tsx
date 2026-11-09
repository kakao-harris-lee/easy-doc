import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { PrivacyPolicyPage } from './PrivacyPolicyPage'

describe('개인정보처리방침 화면', () => {
  it('원본(src/content/legal/privacy-policy.md)의 제목과 버전 문자열을 그린다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByRole('heading', { name: '개인정보처리방침', level: 1 })).toBeInTheDocument()
    // 머리말의 버전과 §10 변경 이력에 같은 문자열이 나온다.
    expect(screen.getAllByText('privacy-2026-11-10').length).toBeGreaterThan(0)
  })

  it('유료 조건의 검토일과 적용일 공고 조건을 보여준다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByText(/검토일: 2026-11-10/)).toBeInTheDocument()
    expect(screen.getByText(/유료 거래 관련 변경 적용일: 2026년 11월 1일/)).toBeInTheDocument()
    expect(screen.queryByText(/유료 거래 관련 변경 적용일: 출시 전 공고/)).not.toBeInTheDocument()
    expect(screen.queryByText(/이 문서는 초안이며 아직 게시하지 않았다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/확인 필요/)).not.toBeInTheDocument()
  })

  it('그림 생성 요청에는 저장 안 함 옵션을 지정할 수 없다는 사실을 보여준다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getByText(/Images API는 이 옵션을 제공하지 않으므로/)).toBeInTheDocument()
  })

  it('표(GFM)를 실제 table 요소로 렌더한다', () => {
    render(<PrivacyPolicyPage />)

    expect(screen.getAllByRole('table').length).toBeGreaterThan(0)
  })
})
