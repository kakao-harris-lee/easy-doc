import { useState } from 'react'
import type { KeyboardEvent } from 'react'
import { CircleCheck } from 'lucide-react'

import { ApiError } from '../api/client'
import type { UserIdentityResponse } from '../api/types'
import { startGoogleLink } from '../auth/googleLink'
import { cn } from '../lib/utils'
import { Button } from './ui/Button'

/** 시작 요청 자체가 실패했을 때(네트워크 등) 보여줄 문구. 서버 문구가 있으면 그것을 우선한다. */
const START_ERROR_MESSAGE = '구글 계정 연결을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'

interface GoogleLinkStatusProps {
  identities: UserIdentityResponse[]
  className?: string
  /** 패널을 감싸는 컨테이너의 Esc 처리에 이 요소도 걸리게 한다(AccountMenu 전용). */
  onButtonKeyDown?: (event: KeyboardEvent<HTMLButtonElement>) => void
}

/**
 * 계정 메뉴·모바일 메뉴가 함께 쓰는 "연결된 계정" 조각.
 *
 * 구글이 연결돼 있으면 그 사실만 보여주고(끊기는 이번 변경 단위 밖 — backlog §1.4의
 * 다음 조각), 아니면 연결을 시작하는 버튼을 그린다. 시작 자체가 실패해도(네트워크 등)
 * 이 조각 안에서만 오류를 보여준다 — 계정 메뉴의 다른 행동(로그아웃)을 막을 이유가
 * 아니다(`GoogleLoginButton`과 같은 원칙).
 */
export function GoogleLinkStatus({
  identities,
  className,
  onButtonKeyDown,
}: GoogleLinkStatusProps) {
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const isGoogleLinked = identities.some((identity) => identity.provider === 'google')

  async function handleClick(): Promise<void> {
    setError(null)
    setStarting(true)
    try {
      await startGoogleLink()
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : START_ERROR_MESSAGE)
      setStarting(false)
    }
  }

  return (
    <div className={cn('border-t border-border pt-3', className)}>
      <p className="text-xs font-semibold text-muted-foreground">연결된 계정</p>
      {isGoogleLinked ? (
        <p className="mt-1.5 flex items-center gap-2 text-sm font-medium text-foreground">
          <CircleCheck className="size-4 shrink-0 text-success" aria-hidden="true" />
          구글 계정 연결됨
        </p>
      ) : (
        <>
          <Button
            variant="ghost"
            type="button"
            className="mt-1 min-h-11 w-full justify-start"
            loading={starting}
            onClick={() => void handleClick()}
            onKeyDown={onButtonKeyDown}
          >
            구글 계정 연결
          </Button>
          {error !== null && (
            <p className="form-error mt-1" role="alert">
              {error}
            </p>
          )}
        </>
      )}
    </div>
  )
}
