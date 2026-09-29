import { type MouseEvent } from 'react'
import { Gift } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useAuth } from '../auth/context'
import { ACCOUNT_SETTINGS_PATH, EMAIL_VERIFICATION_PATH } from '../routes/paths'
import { CONTAINER } from './layoutStyles'

interface PhoneVerificationBannerProps {
  onNavigate: (event: MouseEvent<HTMLAnchorElement>) => void
}

/**
 * 로그인했지만 휴대폰 인증이 남은 사용자에게 무료 체험의 다음 행동을 알려준다.
 */
export function PhoneVerificationBanner({ onNavigate }: PhoneVerificationBannerProps) {
  const { user } = useAuth()

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
      <div className={`${CONTAINER} flex items-center gap-3 py-2`}>
        <Gift className="size-[18px] shrink-0 text-warning" aria-hidden="true" />
        <div className="min-w-0 flex-1 sm:flex sm:items-baseline sm:gap-3">
          <h2 id="phone-trial-heading" className="break-keep text-sm font-bold text-foreground">
            휴대폰 인증하고 체험 5크레딧 받기
          </h2>
          <p className="sr-only text-sm text-muted-foreground sm:not-sr-only">
            {needsEmailVerification
              ? '이메일 인증을 먼저 마쳐 주세요.'
              : '인증을 마치면 샘플 변환용 5크레딧을 한 번 드립니다.'}
          </p>
        </div>
        <Link
          to={target}
          onClick={onNavigate}
          className="inline-flex min-h-11 shrink-0 items-center justify-center rounded-md bg-primary px-3 text-sm font-semibold text-primary-foreground no-underline hover:bg-primary-hover"
        >
          {action}
        </Link>
      </div>
    </section>
  )
}
