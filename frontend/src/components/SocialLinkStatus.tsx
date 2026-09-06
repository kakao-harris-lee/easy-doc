import { useId, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { CircleCheck } from 'lucide-react'

import { oauthUnlink } from '../api/auth'
import { ApiError } from '../api/client'
import type { OAuthProvider, UserIdentityResponse } from '../api/types'
import { PROVIDER_DISPLAY_NAME, SUPPORTED_PROVIDERS, startSocialLink } from '../auth/socialLogin'
import { cn } from '../lib/utils'
import { Button } from './ui/Button'
import { ModalDialog } from './ui/Dialog'

/** 시작 요청 자체가 실패했을 때(네트워크 등) 보여줄 문구. 서버 문구가 있으면 그것을 우선한다. */
const START_ERROR_MESSAGE: Record<OAuthProvider, string> = {
  google: '구글 계정 연결을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  kakao: '카카오 계정 연결을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  naver: '네이버 계정 연결을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
}

/** 해제 요청 자체가 실패했을 때(네트워크 등) 보여줄 문구. 서버 문구(404·409)가 있으면 그것을 우선한다. */
const UNLINK_ERROR_MESSAGE: Record<OAuthProvider, string> = {
  google: '구글 계정 연결 해제에 실패했습니다. 잠시 후 다시 시도해 주세요.',
  kakao: '카카오 계정 연결 해제에 실패했습니다. 잠시 후 다시 시도해 주세요.',
  naver: '네이버 계정 연결 해제에 실패했습니다. 잠시 후 다시 시도해 주세요.',
}

/**
 * 마지막 로그인 수단인 신원을 해제하려 할 때 보여줄 안내 — 서버 409 문구와 같은 뜻이다
 * (계약 `oauthUnlink` 409 예시, `SocialLoginService.LAST_LOGIN_METHOD_MESSAGE`).
 *
 * 비밀번호 설정을 함께 안내한다(backlog §1.4 후속 — `POST /auth/password`가 열리면서
 * 갱신. 이전에는 그 엔드포인트가 없어 다른 소셜 계정 연결만 안내했다). 지금은 탈출구가
 * 둘이다 — 계정 메뉴의 「비밀번호 만들기」(`SetPasswordForm`)와 다른 소셜 계정 연결.
 */
const LAST_LOGIN_METHOD_HINT =
  '마지막 로그인 수단은 해제할 수 없습니다. 먼저 비밀번호를 만들거나 다른 소셜 계정을 연결하세요.'

interface SocialLinkStatusProps {
  identities: UserIdentityResponse[]
  /**
   * 비밀번호가 있는지(`UserResponse.has_password`, 2.17.0 신설). `identities.length === 1`
   * 이면서 이 값이 거짓이면 그 하나뿐인 신원의 연결 해제가 서버에서 409(마지막 로그인
   * 수단)로 거절된다 — 그 갈래를 요청 전에 미리 판정해 버튼을 비활성화한다.
   */
  hasPassword: boolean
  className?: string
  /** 패널을 감싸는 컨테이너의 Esc 처리에 이 요소도 걸리게 한다(AccountMenu 전용). */
  onButtonKeyDown?: (event: KeyboardEvent<HTMLButtonElement>) => void
  /** 연결 해제 성공 뒤 호출된다 — 호출한 쪽(`AppLayout`)이 `/auth/me`를 다시 읽는다. */
  onUnlinked?: () => void
}

/**
 * 계정 메뉴·모바일 메뉴가 함께 쓰는 "연결된 계정" 조각.
 *
 * 지원하는 제공자(구글·카카오·네이버)마다 한 줄씩 보여준다 — 연결돼 있으면 「연결 해제」
 * 버튼을 함께, 아니면 연결을 시작하는 버튼을 그린다(연결 해제는 backlog §1.4 다음 조각,
 * 2.17.0). 해제는 되돌릴 수 없는 조작(그 계정으로 다시 로그인하려면 처음부터 다시
 * 연결해야 한다)이라 `window.confirm` 대신 `ModalDialog`로 먼저 확인한다 —
 * `WorkspaceMenu`·`HistoryPage`의 파기 확인과 같은 원칙이다.
 */
export function SocialLinkStatus({
  identities,
  hasPassword,
  className,
  onButtonKeyDown,
  onUnlinked,
}: SocialLinkStatusProps) {
  const [confirmProvider, setConfirmProvider] = useState<OAuthProvider | null>(null)
  const [unlinking, setUnlinking] = useState(false)
  const [rowNotice, setRowNotice] = useState<{ provider: OAuthProvider; message: string } | null>(
    null,
  )
  const [rowError, setRowError] = useState<{ provider: OAuthProvider; message: string } | null>(
    null,
  )
  const titleId = useId()
  const descriptionId = useId()

  // 마지막 로그인 수단 — 비밀번호가 없고 신원이 하나뿐이면 그 하나는 해제할 수 없다
  // (서버 409, `x-social-login.explicit_linking` 「연결 해제」 문단과 같은 판정).
  const lastLoginMethod = identities.length === 1 && !hasPassword

  function requestUnlink(provider: OAuthProvider): void {
    setRowNotice(null)
    setRowError(null)
    setConfirmProvider(provider)
  }

  function closeConfirm(): void {
    // 보내는 중에는 닫지 않는다 — 결과를 보여줄 자리가 사라진다(`WorkspaceMenu`와 같은 이유).
    if (unlinking) {
      return
    }
    setConfirmProvider(null)
  }

  async function confirmUnlink(): Promise<void> {
    const provider = confirmProvider
    if (provider === null || unlinking) {
      return
    }
    setUnlinking(true)
    try {
      await oauthUnlink(provider)
      setConfirmProvider(null)
      setRowNotice({ provider, message: `${PROVIDER_DISPLAY_NAME[provider]} 연결을 해제했습니다` })
      onUnlinked?.()
    } catch (caught) {
      setConfirmProvider(null)
      setRowError({
        provider,
        message: caught instanceof ApiError ? caught.message : UNLINK_ERROR_MESSAGE[provider],
      })
      // 404(이미 해제됐거나 애초에 없던 연결)면 이 화면의 상태가 이미 낡았다는 뜻이다 —
      // `/auth/me`를 다시 읽어야 그 행이 화면에서도 사라진다.
      if (caught instanceof ApiError && caught.status === 404) {
        onUnlinked?.()
      }
    } finally {
      setUnlinking(false)
    }
  }

  const confirmName = confirmProvider !== null ? PROVIDER_DISPLAY_NAME[confirmProvider] : ''

  return (
    <div className={cn('border-t border-border pt-3', className)}>
      <p className="text-xs font-semibold text-muted-foreground">연결된 계정</p>
      {SUPPORTED_PROVIDERS.map((provider) => (
        <ProviderLinkRow
          key={provider}
          provider={provider}
          linked={identities.some((identity) => identity.provider === provider)}
          disableUnlink={lastLoginMethod}
          onButtonKeyDown={onButtonKeyDown}
          onRequestUnlink={requestUnlink}
        />
      ))}
      {/*
        목록 수준에 하나만 둔다(연결된 신원 개수·연결 여부와 무관) — 방금 해제한 행은
        `onUnlinked`가 부른 `refreshMe` 뒤 `identities`에서 사라져 "연결됨" 가지가 아니라
        "연결" 버튼 가지로 바뀐다. 안내를 그 행 안에 두면 행이 가지를 바꾸는 순간 함께
        사라진다 — 안내가 정작 보여야 할 때(방금 해제했다는 확인) 사라지는 것을 막는다.
      */}
      {rowNotice !== null && (
        <p className="form-success mt-1.5" role="status">
          {rowNotice.message}
        </p>
      )}
      {rowError !== null && (
        <p className="form-error mt-1.5" role="alert">
          {rowError.message}
        </p>
      )}
      <ModalDialog
        open={confirmProvider !== null}
        onClose={closeConfirm}
        labelledBy={titleId}
        describedBy={descriptionId}
      >
        <h2 className="m-0 text-lg font-bold text-foreground" id={titleId}>
          {confirmName} 계정 연결 해제
        </h2>
        <p className="mt-2 mb-5 text-sm text-muted-foreground" id={descriptionId}>
          {confirmName} 계정 연결을 해제할까요? 이 계정으로 다시 로그인하려면 처음부터 다시 연결해야
          합니다.
        </p>
        <div className="flex justify-end gap-2">
          {/* 터치 대상 44×44px 이상 (§10). */}
          <Button
            className="min-h-11 min-w-11"
            variant="outline"
            size="md"
            type="button"
            disabled={unlinking}
            onClick={closeConfirm}
            data-dialog-autofocus=""
          >
            취소
          </Button>
          <Button
            className="min-h-11 min-w-11"
            variant="danger"
            size="md"
            type="button"
            loading={unlinking}
            onClick={() => void confirmUnlink()}
          >
            {unlinking ? '해제하는 중…' : '연결 해제'}
          </Button>
        </div>
      </ModalDialog>
    </div>
  )
}

interface ProviderLinkRowProps {
  provider: OAuthProvider
  linked: boolean
  /** 이 신원이 마지막 로그인 수단이라 해제 버튼을 비활성화해야 하는지. */
  disableUnlink: boolean
  onButtonKeyDown?: (event: KeyboardEvent<HTMLButtonElement>) => void
  onRequestUnlink: (provider: OAuthProvider) => void
}

/**
 * 시작 자체가 실패해도(네트워크 등) 이 줄 안에서만 오류를 보여준다 — 계정 메뉴의 다른
 * 행동(로그아웃)을 막을 이유가 아니다(`SocialLoginButton`과 같은 원칙). 해제 성공·실패
 * 안내는 이 행이 아니라 `SocialLinkStatus` 목록 수준에 있다 — 해제가 성공하면 이 행은
 * `linked`가 거짓으로 바뀌어 사라지기 때문이다(그 컴포넌트 KDoc).
 */
function ProviderLinkRow({
  provider,
  linked,
  disableUnlink,
  onButtonKeyDown,
  onRequestUnlink,
}: ProviderLinkRowProps) {
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const name = PROVIDER_DISPLAY_NAME[provider]
  const reasonId = useId()

  async function handleClick(): Promise<void> {
    setStartError(null)
    setStarting(true)
    try {
      await startSocialLink(provider)
    } catch (caught) {
      setStartError(caught instanceof ApiError ? caught.message : START_ERROR_MESSAGE[provider])
      setStarting(false)
    }
  }

  if (linked) {
    return (
      <div className="mt-1.5">
        <div className="flex items-center justify-between gap-2">
          <p className="flex items-center gap-2 text-sm font-medium text-foreground">
            <CircleCheck className="size-4 shrink-0 text-success" aria-hidden="true" />
            {name} 계정 연결됨
          </p>
          <Button
            variant="ghost"
            size="sm"
            type="button"
            className="min-h-11 shrink-0"
            disabled={disableUnlink}
            aria-describedby={disableUnlink ? reasonId : undefined}
            onClick={() => onRequestUnlink(provider)}
            onKeyDown={onButtonKeyDown}
          >
            연결 해제
          </Button>
        </div>
        {disableUnlink && (
          <p className="mt-1 text-xs text-muted-foreground" id={reasonId}>
            {LAST_LOGIN_METHOD_HINT}
          </p>
        )}
      </div>
    )
  }

  return (
    <>
      <Button
        variant="ghost"
        type="button"
        className="mt-1 min-h-11 w-full justify-start"
        loading={starting}
        onClick={() => void handleClick()}
        onKeyDown={onButtonKeyDown}
      >
        {name} 계정 연결
      </Button>
      {startError !== null && (
        <p className="form-error mt-1" role="alert">
          {startError}
        </p>
      )}
    </>
  )
}
