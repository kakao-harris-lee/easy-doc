import { useEffect, type MouseEvent } from 'react'
import { Check, Gift, Smartphone } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { writePhoneVerificationCookie } from '../auth/phoneVerificationCookie'
import { ACCOUNT_SETTINGS_PATH, EMAIL_VERIFICATION_PATH } from '../routes/paths'
import { CONTAINER } from './layoutStyles'

interface PhoneVerificationBannerProps {
  onNavigate: (event: MouseEvent<HTMLAnchorElement>) => void
}

/**
 * 로그인했지만 휴대폰 인증이 남은 사용자에게 무료 체험의 다음 행동을 알려준다.
 *
 * 쿠키는 서버 상태를 그대로 복사할 뿐 배너의 정본으로 읽지 않는다. 같은 브라우저에서
 * 다른 계정으로 로그인해도 이전 계정의 쿠키 때문에 잘못 숨겨지지 않게 하기 위해서다.
 */
export function PhoneVerificationBanner({ onNavigate }: PhoneVerificationBannerProps) {
  const { user } = useAuth()

  useEffect(() => {
    if (user !== null) {
      writePhoneVerificationCookie(user.phone_verified)
    }
  }, [user])

  if (user === null || user.phone_verified) {
    return null
  }

  const needsEmailVerification = !user.email_verified
  const target = needsEmailVerification ? EMAIL_VERIFICATION_PATH : ACCOUNT_SETTINGS_PATH
  const action = needsEmailVerification ? '이메일 인증하기' : '휴대폰 인증하기'

  return (
    <section
      className="border-b border-warning/30 bg-warning-surface"
      aria-labelledby="phone-trial-heading"
    >
      <div className={`${CONTAINER} flex flex-col gap-4 py-4 lg:flex-row lg:items-center`}>
        <div className="min-w-0 flex-1">
          <h2
            id="phone-trial-heading"
            className="flex items-center gap-2 text-sm font-bold text-foreground"
          >
            <Gift className="size-[18px] shrink-0 text-warning" aria-hidden="true" />
            휴대폰 인증하고 체험 5크레딧 받기
          </h2>
          <p className="mt-1 text-sm leading-[22px] text-muted-foreground">
            {needsEmailVerification
              ? '이메일 인증을 먼저 완료한 뒤 휴대폰 인증번호를 요청해 주세요. 인증을 마치면 샘플 변환용 5크레딧을 한 번 드립니다.'
              : '계정 설정에서 휴대폰 인증번호를 요청하고 확인하면 샘플 변환용 5크레딧을 한 번 드립니다.'}
          </p>
          <ol className="mt-3 flex flex-wrap gap-x-4 gap-y-2 text-xs text-foreground">
            <li className="flex items-center gap-1.5">
              <Check className="size-4 text-success" aria-hidden="true" /> 가입 완료
            </li>
            <li className="flex items-center gap-1.5">
              <Check className="size-4 text-success" aria-hidden="true" /> 로그인 완료
            </li>
            <li className="flex items-center gap-1.5 font-semibold">
              <Smartphone className="size-4 text-warning" aria-hidden="true" /> 휴대폰 인증 필요
            </li>
            <li className="text-muted-foreground">인증 완료 → 5크레딧 발급</li>
          </ol>
        </div>
        <Link
          to={target}
          onClick={onNavigate}
          className="inline-flex min-h-11 shrink-0 items-center justify-center rounded-md bg-primary px-4 text-sm font-semibold text-primary-foreground no-underline hover:bg-primary-hover"
        >
          {action}
        </Link>
      </div>
    </section>
  )
}
