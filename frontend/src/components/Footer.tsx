import { Link } from 'react-router-dom'

import { BUSINESS_INFO_LOOKUP_URL, COMPANY_INFO, type CompanyInfo } from '../config/company'
import { cn } from '../lib/utils'
import { GUIDE_PATH, PRIVACY_PATH, TERMS_PATH } from '../routes/paths'
import { CONTAINER } from './AppLayout'

/** 정책 열의 일반 링크 — 개인정보처리방침만 이 모양과 구분되게 그린다(아래 참고). */
const POLICY_LINK_CLASS =
  'text-muted-foreground underline-offset-2 hover:text-foreground hover:underline'

/**
 * 사이트 푸터(P0-13, 계획 `docs/plans/2026-09-09-legal-footer-and-support.md` §5.2).
 *
 * 로그인 여부와 무관하게 모든 화면에 나와야 한다는 요건(전자상거래법의 사업자 정보
 * 초기 화면 표시 의무)이 있어, `AppRoutes`가 아니라 `AppLayout`에 둔다 — `AppLayout`은
 * `App.tsx`에서 인증 상태를 가리지 않고 모든 라우트를 감싸므로(로그인·가입 화면 포함),
 * 이 컴포넌트를 `status === 'authenticated'` 조건 밖에 두는 것만으로 로그인 전
 * 화면에도 그려진다. 머리말의 메뉴들과 달리 조건부 렌더를 걸지 않는 이유다.
 *
 * 이용 가이드는 「정책」 열에 둔다. 요금 안내 화면이 아직 없어 계획이 제안한
 * 「서비스」 열은 만들지 않는다 — 링크할 화면이 생기면 그때 넷째 열로 추가한다.
 *
 * 계획 §5.2가 「정책」 열에 나열한 항목 중 **환불 정책**과 **AI 윤리**도 이번에는
 * 뺐다 — 이유가 서로 다르다.
 * - 환불 정책: 지금은 무료 파일럿이다. 약관 제9조가 「대가를 받지 않는다」고 명시하므로
 *   환불할 결제 자체가 없다. 유료 결제(§1.0 스텁 → §14 실연동)를 열 때 약관 제9조를
 *   확정하면서 이 자리에 링크를 더한다.
 * - AI 윤리: 계획이 처음부터 「선택」으로 둔 항목이고, 문서 자체가 아직 없다. 문서가
 *   생기면 이 자리에 링크를 더한다.
 * 둘 다 지금은 가리킬 화면·문서가 없다 — 없는 곳으로 가는 죽은 링크를 두지 않는다.
 *
 * `company`는 기본값이 실제 구성값(`COMPANY_INFO`)이고, 화면에서는 이 매개변수를
 * 넘기지 않는다 — 테스트가 전화번호·신고번호를 채운 값과 비운 값을 각각 주입해
 * 「값이 없으면 행이 안 보이고, 있으면 보인다」 두 갈래를 실제로 재기 위한
 * 자리일 뿐이다(`Footer.test.tsx`).
 */
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
      <div className={cn(CONTAINER, 'py-10')}>
        <div className="grid gap-8 sm:grid-cols-3">
          <div>
            <h2 className="mb-3 text-sm font-semibold text-foreground">회사</h2>
            <ul className="space-y-1 text-sm text-muted-foreground">
              <li>{name}</li>
              <li>대표 {representativeName}</li>
              <li>{address}</li>
              <li>사업자등록번호 {businessRegistrationNumber}</li>
              {/* 확정되지 않은 값은 행 자체를 그리지 않는다 — 라벨만 남고 값이 빈
                  줄을 두면 나중에 값이 빠져도 조용히 깨진다(전화·신고번호 모두). */}
              {phoneNumber !== null && <li>전화 {phoneNumber}</li>}
              {mailOrderRegistrationNumber !== null && (
                <li>통신판매업 신고번호 {mailOrderRegistrationNumber}</li>
              )}
              <li>
                개인정보 보호책임자 {privacyOfficerName} ({privacyOfficerRole})
              </li>
            </ul>
          </div>
          <div>
            <h2 className="mb-3 text-sm font-semibold text-foreground">고객지원</h2>
            <ul className="space-y-1 text-sm text-muted-foreground">
              <li>
                <a
                  href={`mailto:${supportEmail}`}
                  className="hover:text-foreground hover:underline"
                >
                  {supportEmail}
                </a>
              </li>
              <li>{businessHours}</li>
            </ul>
          </div>
          <div>
            <h2 className="mb-3 text-sm font-semibold text-foreground">정책</h2>
            <ul className="space-y-2 text-sm">
              <li>
                <Link to={GUIDE_PATH} className={POLICY_LINK_CLASS}>
                  이용 가이드
                </Link>
              </li>
              <li>
                <Link to={TERMS_PATH} className={POLICY_LINK_CLASS}>
                  이용약관
                </Link>
              </li>
              <li>
                {/*
                  개인정보 보호법이 요구하는 구분 표시 — 굵기만이 아니라 글자색도
                  다른 정책 링크(muted)보다 진하게(foreground) 두고 밑줄을 항상
                  보이게 해 대비가 실제로 드러나게 한다.
                */}
                <Link
                  to={PRIVACY_PATH}
                  className="font-bold text-foreground underline decoration-2 underline-offset-2"
                >
                  개인정보처리방침
                </Link>
              </li>
              <li>
                {/* 공정거래위원회 사업자정보 확인 — 외부 링크. 주소가 실제로 열리는지는
                    확인하지 못했다(config/company.ts 주석 참고). */}
                <a
                  href={BUSINESS_INFO_LOOKUP_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className={POLICY_LINK_CLASS}
                >
                  사업자정보 확인
                </a>
              </li>
            </ul>
          </div>
        </div>
        <p className="mt-8 border-t border-border pt-4 text-xs text-muted-foreground">
          {/*
            회사명 뒤 조사가 이름에 따라 갈린다(받침 유무로 「이/가」가 바뀐다) — 조사
            판정 로직을 만드는 대신 조사가 필요 없는 「에서」로 문장을 고른다. 회사명은
            여전히 구성값에서 온다.
          */}
          이 사이트는 {name}에서 운영합니다. © {serviceLaunchYear} {name}. All rights reserved.
        </p>
      </div>
    </footer>
  )
}
