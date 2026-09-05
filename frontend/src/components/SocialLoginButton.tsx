import { useState } from 'react'

import type { OAuthProvider } from '../api/types'
import { ApiError } from '../api/client'
import { PROVIDER_LOGIN_LABEL, startSocialLogin } from '../auth/socialLogin'
import { cn } from '../lib/utils'
import { Button } from './ui/Button'

interface SocialLoginButtonProps {
  provider: OAuthProvider
}

/** 시작 요청 자체가 실패했을 때(네트워크 등) 보여줄 문구. 서버 문구가 있으면 그것을 우선한다. */
const GENERIC_START_ERROR_MESSAGE: Record<OAuthProvider, string> = {
  google: '구글 로그인을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  kakao: '카카오 로그인을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
}

/**
 * 카카오 로그인 버튼 브랜드 규격 — 배경 `#FEE500`, 텍스트·아이콘은 검정만 쓴다(다른
 * 색을 섞지 않는다). `#000000` 글자와 `#FEE500` 배경의 명암비는 약 15:1로 4.5:1
 * 기준을 넉넉히 넘는다.
 */
const KAKAO_BUTTON_CLASSNAME =
  'border-transparent bg-[#FEE500] text-black hover:bg-[#FEE500]/90 disabled:opacity-50 disabled:hover:bg-[#FEE500]'

/**
 * "OO로 계속하기" 버튼. 로그인·가입 화면 공용이고 `provider`로 구글·카카오를 가른다 —
 * 소셜 로그인은 두 화면에서 결과가 똑같이 `TokenResponse`이므로(로그인이든 첫 연결이든)
 * 같은 시작 흐름(`startSocialLogin`)을 그대로 쓴다.
 *
 * 시작 요청이 422(제공자 미설정 등)로 실패해도 이메일 폼은 그대로 써야 하므로, 이 버튼
 * 아래에만 오류를 남기고 `CredentialsForm`의 오류 상태는 건드리지 않는다.
 */
export function SocialLoginButton({ provider }: SocialLoginButtonProps) {
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)

  async function handleClick() {
    setError(null)
    setStarting(true)
    try {
      await startSocialLogin(provider)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : GENERIC_START_ERROR_MESSAGE[provider])
      setStarting(false)
    }
  }

  return (
    <div className="mt-4">
      <Button
        type="button"
        variant="outline"
        // 44px 높이 — 카카오 로그인 버튼 가이드의 최소 규격을 구글 버튼과도 통일한다.
        className={cn('h-11', provider === 'kakao' && KAKAO_BUTTON_CLASSNAME)}
        fullWidth
        loading={starting}
        onClick={handleClick}
      >
        {provider === 'google' ? <GoogleIcon /> : <KakaoIcon />}
        {PROVIDER_LOGIN_LABEL[provider]}
      </Button>
      {error !== null && (
        <p className="form-error mt-2" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

function GoogleIcon() {
  return (
    <svg aria-hidden="true" width="18" height="18" viewBox="0 0 18 18" className="shrink-0">
      <path
        fill="#4285F4"
        d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.9c1.7-1.57 2.7-3.87 2.7-6.62Z"
      />
      <path
        fill="#34A853"
        d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.9-2.26c-.8.54-1.84.86-3.06.86-2.35 0-4.34-1.59-5.05-3.72H.96v2.33A9 9 0 0 0 9 18Z"
      />
      <path
        fill="#FBBC05"
        d="M3.95 10.7A5.4 5.4 0 0 1 3.67 9c0-.59.1-1.17.28-1.7V4.97H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.03l2.99-2.33Z"
      />
      <path
        fill="#EA4335"
        d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.97l2.99 2.33C4.66 5.17 6.65 3.58 9 3.58Z"
      />
    </svg>
  )
}

/** 단순한 말풍선 하나만 그린다(카카오 로그인 버튼 가이드 — 아이콘을 복잡하게 꾸미지 않는다). */
function KakaoIcon() {
  return (
    <svg aria-hidden="true" width="18" height="18" viewBox="0 0 18 18" className="shrink-0">
      <path
        fill="#000000"
        d="M9 2.2C4.7 2.2 1.3 4.9 1.3 8.2c0 2.1 1.4 3.9 3.5 5-.15.55-.55 2.02-.63 2.35-.1.4.15.4.31.29.13-.09 2.05-1.36 2.87-1.92.51.07 1.04.11 1.58.11 4.3 0 7.7-2.7 7.7-6 0-3.4-3.4-6.1-7.7-6.1Z"
      />
    </svg>
  )
}
