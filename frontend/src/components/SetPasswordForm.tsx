import { useId, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'

import { setPassword } from '../api/auth'
import { ApiError } from '../api/client'
import { MIN_PASSWORD_LENGTH, validatePassword } from '../auth/validation'
import { cn } from '../lib/utils'
import { Button } from './ui/Button'

const GENERIC_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 다시 시도해 주세요.'

interface SetPasswordFormProps {
  className?: string
  onButtonKeyDown?: (event: KeyboardEvent<HTMLButtonElement>) => void
  /** 설정 성공 뒤 호출된다 — 호출한 쪽(`AppLayout`)이 `/auth/me`를 다시 읽는다. */
  onCreated?: () => void
}

/**
 * 계정 메뉴가 `SocialLinkStatus`와 나란히 쓰는 "비밀번호 만들기" 조각(2.19.0 신설,
 * backlog §1.4 다음 조각).
 *
 * `me.has_password === false`(소셜 로그인으로만 가입한 계정)일 때만 부모가 이 컴포넌트를
 * 렌더링한다 — 이미 비밀번호가 있으면 만들 것이 없다. 「비밀번호 만들기」 버튼을 누르면
 * 작은 인라인 폼(새 비밀번호 + 확인)이 펼쳐지고, `CredentialsForm`의 가입 화면과 같은
 * 검증 안내(최소 길이)를 쓴다 — 다만 이메일 필드가 없다(이미 인증된 사용자다).
 */
export function SetPasswordForm({ className, onButtonKeyDown, onCreated }: SetPasswordFormProps) {
  const [open, setOpen] = useState(false)
  const [password, setPasswordValue] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const passwordId = useId()
  const confirmId = useId()

  function openForm(): void {
    setOpen(true)
    setSuccess(false)
    setFormError(null)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    const nextPasswordError = validatePassword(password)
    setPasswordError(nextPasswordError)
    setFormError(null)
    if (nextPasswordError !== null) {
      return
    }
    if (password !== confirmPassword) {
      setFormError('비밀번호가 서로 다릅니다')
      return
    }
    setSubmitting(true)
    try {
      await setPassword(password)
      setOpen(false)
      setPasswordValue('')
      setConfirmPassword('')
      setSuccess(true)
      onCreated?.()
    } catch (caught) {
      setFormError(caught instanceof ApiError ? caught.message : GENERIC_ERROR_MESSAGE)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={cn('border-t border-border pt-3', className)}>
      {!open && (
        <Button
          variant="ghost"
          type="button"
          className="min-h-11 w-full justify-start"
          onClick={openForm}
          onKeyDown={onButtonKeyDown}
        >
          비밀번호 만들기
        </Button>
      )}
      {success && (
        <p className="form-success mt-1.5" role="status">
          비밀번호를 만들었습니다
        </p>
      )}
      {open && (
        <form
          className="mt-1.5 flex flex-col gap-2"
          onSubmit={(event) => void handleSubmit(event)}
          noValidate
        >
          {formError !== null && (
            <p className="form-error" role="alert">
              {formError}
            </p>
          )}
          <div className="field">
            <label htmlFor={passwordId}>새 비밀번호</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base text-foreground"
              id={passwordId}
              type="password"
              autoComplete="new-password"
              value={password}
              aria-invalid={passwordError !== null}
              aria-describedby={
                passwordError !== null ? `${passwordId}-error` : `${passwordId}-hint`
              }
              onChange={(event) => setPasswordValue(event.target.value)}
            />
            {passwordError === null ? (
              <p className="field-hint" id={`${passwordId}-hint`}>
                {MIN_PASSWORD_LENGTH}자 이상 입력해 주세요.
              </p>
            ) : (
              <p className="field-error" id={`${passwordId}-error`} role="alert">
                {passwordError}
              </p>
            )}
          </div>
          <div className="field">
            <label htmlFor={confirmId}>새 비밀번호 확인</label>
            <input
              className="h-11 w-full rounded-[10px] border border-input bg-card px-3.5 text-base text-foreground"
              id={confirmId}
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              type="button"
              disabled={submitting}
              onClick={() => {
                setOpen(false)
                setPasswordValue('')
                setConfirmPassword('')
                setPasswordError(null)
                setFormError(null)
              }}
            >
              취소
            </Button>
            <Button type="submit" loading={submitting}>
              {submitting ? '만드는 중…' : '만들기'}
            </Button>
          </div>
        </form>
      )}
    </div>
  )
}
