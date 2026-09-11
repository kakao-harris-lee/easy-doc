/**
 * 회사 정보 — 구성값 한 곳 (master-plan, `docs/plans/2026-09-09-legal-footer-and-support.md`
 * §7-8 결정: 「회사 정보는 구성값 한 곳에 두어 나중 이전을 값 변경으로 끝낸다」).
 *
 * 사업장 주소·상호가 바뀌어도 이 파일 하나만 고치면 푸터·약관·개인정보처리방침
 * 화면(약관·방침 본문 자체는 `src/content/legal/*.md`가 원본이라 이 파일이 아니라 그쪽을
 * 고친다)에 값이 반영된다. JSX에 이 값들을 직접 박지 않는다.
 *
 * 전화번호와 통신판매업 신고번호는 아직 확정되지 않았다(같은 계획 §7-6·§7-10) —
 * `null`을 허용하고, 화면 쪽에서 `null`이면 해당 행을 아예 그리지 않는다. 값이
 * 있는 라벨만 남고 값이 빈 줄을 만들지 않기 위해서다.
 */
export interface CompanyInfo {
  /** 사업자등록증의 상호. 서비스명(이지닥)과 다르다. */
  readonly name: string
  readonly representativeName: string
  readonly businessRegistrationNumber: string
  readonly address: string
  readonly privacyOfficerName: string
  readonly privacyOfficerRole: string
  readonly supportEmail: string
  /** 게시용 운영시간 문구. 「즉시 응답」이 아니라 「목표」로 적는다(계획 §6.1). */
  readonly businessHours: string
  /** 게시 여부 확인 중 — 확정 전까지 `null`. */
  readonly phoneNumber: string | null
  /** 유료 결제 개시 전 신고 예정 — 신고 전까지 `null`. */
  readonly mailOrderRegistrationNumber: string | null
  /**
   * 서비스 최초 공개 연도(1차 배포). 사업 개시일이 아니다 — 카피라이트 표기는 이
   * 값을 고정하고 해마다 자동으로 올리지 않는다(계획 §5.3).
   */
  readonly serviceLaunchYear: number
}

export const COMPANY_INFO: CompanyInfo = {
  name: '몬딱 솔루션',
  representativeName: '이치훈',
  businessRegistrationNumber: '671-47-01204',
  address: '제주특별자치도 제주시 첨단로동길 106, 312동 403호',
  privacyOfficerName: '이치훈',
  privacyOfficerRole: '대표',
  supportEmail: 'mobydick@hanmail.net',
  businessHours: '영업일 09:00~18:00 (당일 회신을 목표로 합니다)',
  phoneNumber: null,
  mailOrderRegistrationNumber: null,
  // ⟨확인 필요⟩ 1차 배포 시점의 실제 공개 연도로 다시 확인할 것 — 지금은 예정 연도다.
  serviceLaunchYear: 2026,
}

/**
 * 공정거래위원회 사업자정보 확인 페이지 주소.
 *
 * ⟨확인 필요⟩ 이 주소가 실제로 열리는지는 검증하지 못했다 — 사업자등록번호에서
 * 하이픈을 뺀 10자리를 `wrkr_no` 파라미터로 넘기는 관례를 따랐을 뿐이다(계획
 * `docs/plans/2026-09-09-legal-footer-and-support.md` §5.2). 실제로 열리지 않으면
 * 이 상수만 고치면 된다.
 */
export const BUSINESS_INFO_LOOKUP_URL = `https://www.ftc.go.kr/bizCommPop.do?wrkr_no=${COMPANY_INFO.businessRegistrationNumber.replace(/-/g, '')}`
