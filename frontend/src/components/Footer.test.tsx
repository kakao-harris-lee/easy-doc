import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { COMPANY_INFO, type CompanyInfo } from '../config/company'
import { Footer } from './Footer'

function renderFooter(company?: CompanyInfo) {
  return render(
    <MemoryRouter>
      <Footer company={company} />
    </MemoryRouter>,
  )
}

describe('Footer', () => {
  it('전화번호·통신판매업 신고번호가 없으면 그 행을 그리지 않는다', () => {
    renderFooter({ ...COMPANY_INFO, phoneNumber: null, mailOrderRegistrationNumber: null })

    expect(screen.queryByText(/전화 /)).not.toBeInTheDocument()
    expect(screen.queryByText(/통신판매업 신고번호/)).not.toBeInTheDocument()
  })

  it('값이 있으면 전화번호·통신판매업 신고번호 행을 그린다', () => {
    renderFooter({
      ...COMPANY_INFO,
      phoneNumber: '064-000-0000',
      mailOrderRegistrationNumber: '2026-제주이도-0001',
    })

    expect(screen.getByText('전화 064-000-0000')).toBeInTheDocument()
    expect(screen.getByText('통신판매업 신고번호 2026-제주이도-0001')).toBeInTheDocument()
  })

  it('개인정보처리방침 링크가 다른 정책 링크와 시각적으로 구분된다', () => {
    renderFooter()

    const privacyLink = screen.getByRole('link', { name: '개인정보처리방침' })
    const termsLink = screen.getByRole('link', { name: '이용약관' })

    // 굵기만이 아니라 색(대비)도 달라야 한다 — 개인정보 보호법이 요구하는 구분
    // 표시는 굵기 하나만으로는 색각 이상 사용자 등에게 드러나지 않을 수 있다.
    expect(privacyLink.className).toContain('font-bold')
    expect(privacyLink.className).not.toBe(termsLink.className)
  })

  it('이용 가이드 링크가 /guide 로 걸린다', () => {
    renderFooter()

    expect(screen.getByRole('link', { name: '이용 가이드' })).toHaveAttribute('href', '/guide')
  })

  it('고객지원 이메일이 mailto 링크로 걸린다', () => {
    renderFooter()

    const emailLink = screen.getByRole('link', { name: `고객지원 ${COMPANY_INFO.supportEmail}` })
    expect(emailLink).toHaveAttribute('href', `mailto:${COMPANY_INFO.supportEmail}`)
  })

  it('사업자정보 확인은 새 창으로 여는 외부 링크다', () => {
    renderFooter()

    const lookupLink = screen.getByRole('link', { name: /^사업자정보 확인:/ })
    expect(lookupLink).toHaveAttribute('target', '_blank')
    expect(lookupLink).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('회사명은 한 번만 표시하고 카피라이트에 구성값의 연도를 쓴다', () => {
    renderFooter({ ...COMPANY_INFO, name: '테스트 회사', serviceLaunchYear: 2030 })

    expect(screen.getAllByText('테스트 회사')).toHaveLength(1)
    expect(screen.getByText('© 2030 All rights reserved.')).toBeInTheDocument()
  })

  it('contentinfo 랜드마크와 정책 탐색을 제공한다', () => {
    renderFooter()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: '정책' })).toBeInTheDocument()
  })

  it('좁은 화면에서 회사명과 정책 링크를 서로 다른 행으로 배치한다', () => {
    renderFooter()

    const footerContent = screen.getByRole('contentinfo').firstElementChild
    const companyRow = screen.getByText(COMPANY_INFO.name).parentElement
    const policyNavigation = screen.getByRole('navigation', { name: '정책' })

    expect(footerContent).toHaveClass('space-y-0', 'py-2', 'sm:space-y-1', 'sm:py-4')
    expect(companyRow).toHaveClass('flex-col', 'items-start', 'sm:flex-row', 'sm:items-center')
    expect(policyNavigation).toHaveClass('w-full', 'justify-between', 'sm:w-auto')
  })

  it('사업자 정보 행의 텍스트와 링크를 같은 기준선으로 맞춘다', () => {
    renderFooter()

    const representative = screen.getByText(
      '대표 · 개인정보 보호책임자 ' + COMPANY_INFO.representativeName,
    )
    const businessLookup = screen.getByRole('link', { name: /^사업자정보 확인:/ })

    expect(representative.parentElement).toHaveClass('items-center')
    expect(representative).toHaveClass('inline-flex', 'items-center', 'sm:min-h-11')
    expect(representative).not.toHaveClass('min-h-11')
    expect(businessLookup).toHaveClass('whitespace-nowrap')
  })

  it('대표와 개인정보 보호책임자가 같으면 이름을 합쳐 표시한다', () => {
    renderFooter()
    expect(screen.getAllByText(new RegExp(COMPANY_INFO.representativeName))).toHaveLength(1)
    expect(
      screen.getByText('대표 · 개인정보 보호책임자 ' + COMPANY_INFO.representativeName),
    ).toBeInTheDocument()
  })

  it('개인정보 보호책임자가 다르면 별도로 표시한다', () => {
    renderFooter({ ...COMPANY_INFO, privacyOfficerName: '김담당', privacyOfficerRole: '관리자' })
    expect(screen.getByText('대표 ' + COMPANY_INFO.representativeName)).toBeInTheDocument()
    expect(screen.getByText('개인정보 보호책임자 김담당 (관리자)')).toBeInTheDocument()
  })
})
