import { Link } from 'react-router-dom'

import { BUSINESS_INFO_LOOKUP_URL, COMPANY_INFO, type CompanyInfo } from '../config/company'
import { cn } from '../lib/utils'
import { GUIDE_PATH, PRIVACY_PATH, TERMS_PATH } from '../routes/paths'
import { CONTAINER } from './AppLayout'

const POLICY_LINK_CLASS =
  'inline-flex min-h-11 min-w-11 shrink-0 items-center whitespace-nowrap text-muted-foreground underline-offset-2 hover:text-foreground hover:underline'

/** 링크와 같은 행에 서는 정보 텍스 — 44px 높이와 세로 중앙을 맞춘다. */
const INFO_TEXT_CLASS = 'inline-flex min-h-11 items-center whitespace-nowrap'

/** 모든 화면에서 회사 정보와 정책·문의 링크를 제공한다. */
export function Footer({ company = COMPANY_INFO }: { company?: CompanyInfo } = {}) {
  const {
    name,
    representativeName,
    address,
    businessRegistrationNumber,
    privacyOfficerName,
    privacyOfficerRole,
    supportEmail,
    businessHours,
    phoneNumber,
    mailOrderRegistrationNumber,
    serviceLaunchYear,
  } = company

  return (
    <footer className="border-t border-border bg-secondary/40">
      <div
        className={cn(CONTAINER, 'space-y-1 py-4 text-xs leading-relaxed text-muted-foreground')}
      >
        <div className="flex flex-col items-start sm:flex-row sm:items-center sm:justify-between sm:gap-x-6">
          <span className="font-semibold text-foreground">{name}</span>
          <nav
            aria-label="정책"
            className="flex w-full flex-wrap items-center justify-between gap-x-2 sm:w-auto sm:justify-start sm:gap-x-4"
          >
            <Link to={GUIDE_PATH} className={POLICY_LINK_CLASS}>
              이용 가이드
            </Link>
            <Link to={TERMS_PATH} className={POLICY_LINK_CLASS}>
              이용약관
            </Link>
            <Link
              to={PRIVACY_PATH}
              className="inline-flex min-h-11 shrink-0 items-center whitespace-nowrap font-bold text-foreground underline decoration-2 underline-offset-2"
            >
              개인정보처리방침
            </Link>
          </nav>
        </div>
        <div className="flex flex-wrap items-center gap-x-4">
          <span className={INFO_TEXT_CLASS}>
            {representativeName === privacyOfficerName
              ? `대표 · 개인정보 보호책임자 ${representativeName}`
              : `대표 ${representativeName}`}
          </span>
          {representativeName !== privacyOfficerName && (
            <span className={INFO_TEXT_CLASS}>
              개인정보 보호책임자 {privacyOfficerName} ({privacyOfficerRole})
            </span>
          )}
          <a
            href={BUSINESS_INFO_LOOKUP_URL}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`사업자정보 확인: 사업자등록번호 ${businessRegistrationNumber}`}
            className={POLICY_LINK_CLASS}
          >
            사업자등록번호 {businessRegistrationNumber}
          </a>
          {mailOrderRegistrationNumber !== null && (
            <span className={INFO_TEXT_CLASS}>
              통신판매업 신고번호 {mailOrderRegistrationNumber}
            </span>
          )}
        </div>
        <p>{address}</p>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <a href={`mailto:${supportEmail}`} className={POLICY_LINK_CLASS}>
            고객지원 {supportEmail}
          </a>
          <span className={INFO_TEXT_CLASS}>{businessHours}</span>
          {phoneNumber !== null && <span className={INFO_TEXT_CLASS}>전화 {phoneNumber}</span>}
          <span className={`${INFO_TEXT_CLASS} sm:ml-auto`}>
            © {serviceLaunchYear} All rights reserved.
          </span>
        </div>
      </div>
    </footer>
  )
}
