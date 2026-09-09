import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'
import { AlertTriangle } from 'lucide-react'

import { deleteAccount } from '../api/auth'
import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { listInvoiceRequests } from '../api/invoices'
import { useAuth } from '../auth/context'
import { useWorkspace } from '../workspace/context'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/Button'

/**
 * 탈퇴 확인 문구 — 서버 정본은 `DeleteAccountService.CONFIRMATION_PHRASE`. 화면은 이
 * 값을 검증하지 않고 그대로 보내기만 한다(서버가 판정하는 서비스 층 규칙, `x-service
 * -constraint`) — 틀리면 서버 문구를 그대로 보여준다.
 */
const CONFIRMATION_PHRASE = '탈퇴합니다'

const SUMMARY_LOAD_ERROR_MESSAGE =
  '남은 이용량을 불러오지 못했습니다. 그래도 탈퇴는 진행할 수 있습니다.'
const SUBMIT_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 모든 작업 공간의 가용 크레딧과 처리 대기 세금계산서 요청 수를 합친 값. */
interface AccountSummary {
  remainingCredits: number
  pendingInvoiceRequests: number
}

/**
 * 계정 설정 화면 — 회원 탈퇴(2.27.0 신설, 계획
 * `docs/plans/2026-09-09-account-deletion.md`).
 *
 * 「회원 탈퇴」 버튼을 누르기 전에는 확인 절차를 보여주지 않는다 — 다른 화면의 위험한
 * 조작(작업 공간 삭제 대화상자 등)과 같은 2단계 원칙이다. 누르면 남은 이용량·처리
 * 중인 세금계산서 요청 수·되돌릴 수 없다는 경고와 함께 확인 폼(비밀번호·확인 문구)이
 * 펼쳐진다.
 *
 * 성공(204)하면 `signOut()`만 부른다 — 별도로 이동시키지 않는다. `RequireAuth`가
 * `status`가 `anonymous`로 바뀐 것을 보고 로그인 화면으로 돌려보낸다(`AppLayout`의
 * `guardedSignOut`과 같은 규약).
 */
export function AccountSettingsPage() {
  const { user, signOut } = useAuth()
  const { workspaces } = useWorkspace()

  const [summary, setSummary] = useState<AccountSummary | null>(null)
  const [summaryLoading, setSummaryLoading] = useState(true)
  const [summaryError, setSummaryError] = useState<string | null>(null)

  const [confirming, setConfirming] = useState(false)
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const passwordId = useId()
  const confirmationId = useId()

  // 워크스페이스마다 가용 크레딧·처리 대기 요청을 조회해 합친다. 탈퇴는 계정 전체를
  // 지우므로(작업 공간 하나가 아니라) 지금 보고 있는 작업 공간이 아니라 **전부**를
  // 합산해야 화면이 실제로 사라질 범위를 보여준다.
  useEffect(() => {
    let cancelled = false

    async function load(): Promise<void> {
      if (workspaces.length === 0) {
        setSummary({ remainingCredits: 0, pendingInvoiceRequests: 0 })
        setSummaryLoading(false)
        return
      }
      setSummaryLoading(true)
      setSummaryError(null)
      try {
        const perWorkspace = await Promise.all(
          workspaces.map(async (workspace) => {
            const [credits, invoices] = await Promise.all([
              getWorkspaceCredits(workspace.id),
              listInvoiceRequests(workspace.id),
            ])
            const pending = invoices.items.filter((item) => item.status === 'requested').length
            return { available: credits.available, pending }
          }),
        )
        if (cancelled) {
          return
        }
        setSummary({
          remainingCredits: perWorkspace.reduce((sum, item) => sum + item.available, 0),
          pendingInvoiceRequests: perWorkspace.reduce((sum, item) => sum + item.pending, 0),
        })
      } catch (caught: unknown) {
        if (!cancelled) {
          setSummaryError(caught instanceof ApiError ? caught.message : SUMMARY_LOAD_ERROR_MESSAGE)
        }
      } finally {
        if (!cancelled) {
          setSummaryLoading(false)
        }
      }
    }

    void load()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 워크스페이스 id 조합이 바뀔 때만 다시 부른다.
  }, [workspaces.map((workspace) => workspace.id).join(',')])

  function openConfirm(): void {
    setConfirming(true)
    setSubmitError(null)
  }

  function closeConfirm(): void {
    setConfirming(false)
    setPassword('')
    setConfirmation('')
    setSubmitError(null)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    if (submitting || user === null) {
      return
    }
    setSubmitting(true)
    setSubmitError(null)
    try {
      await deleteAccount({
        password: user.has_password ? password : undefined,
        confirmation,
      })
      signOut()
    } catch (caught: unknown) {
      setSubmitError(caught instanceof ApiError ? caught.message : SUBMIT_ERROR_MESSAGE)
    } finally {
      setSubmitting(false)
    }
  }

  if (user === null) {
    return null
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        context="계정"
        title="계정 설정"
        description="로그인 계정과 회원 탈퇴를 관리합니다."
      />

      <section className="rounded-[12px] border border-border bg-card p-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
        <h2 className="text-[15px] font-semibold text-foreground">로그인 계정</h2>
        <p className="mt-2 text-sm text-foreground">{user.email}</p>
      </section>

      <section
        className="rounded-[12px] border border-danger bg-card p-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]"
        aria-labelledby="account-deletion-heading"
      >
        <h2 id="account-deletion-heading" className="text-[15px] font-semibold text-foreground">
          회원 탈퇴
        </h2>
        <p className="mt-2 text-sm text-muted-foreground">
          계정과 문서, 변환 기록을 포함한 모든 개인정보를 즉시 파기합니다. 되돌릴 수 없습니다.
        </p>

        {!confirming && (
          <Button variant="danger" type="button" className="mt-4" onClick={openConfirm}>
            회원 탈퇴
          </Button>
        )}

        {confirming && (
          <form
            className="mt-4 flex flex-col gap-4"
            noValidate
            onSubmit={(event) => void handleSubmit(event)}
          >
            <div className="form-error flex items-start gap-2" role="alert">
              <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <p className="m-0">
                <strong>이 작업은 되돌릴 수 없습니다.</strong> 계정을 지우면 모든 작업
                공간·문서·변환 결과가 즉시 사라지고 복구할 수 없습니다.
              </p>
            </div>

            <dl className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">남은 이용량</dt>
                <dd className="font-semibold tabular-nums text-foreground">
                  {summaryLoading
                    ? '불러오는 중…'
                    : `${(summary?.remainingCredits ?? 0).toLocaleString('ko-KR')} 크레딧 (소멸, 환불되지 않음)`}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">처리 중인 세금계산서 요청</dt>
                <dd className="font-semibold tabular-nums text-foreground">
                  {summaryLoading
                    ? '불러오는 중…'
                    : `${(summary?.pendingInvoiceRequests ?? 0).toLocaleString('ko-KR')}건 (함께 취소됨)`}
                </dd>
              </div>
            </dl>
            {summaryError !== null && (
              <p className="form-error" role="alert">
                {summaryError}
              </p>
            )}

            {user.has_password && (
              <div className="field">
                <label htmlFor={passwordId}>비밀번호</label>
                <input
                  id={passwordId}
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              </div>
            )}

            <div className="field">
              <label htmlFor={confirmationId}>확인 문구</label>
              <input
                id={confirmationId}
                type="text"
                value={confirmation}
                onChange={(event) => setConfirmation(event.target.value)}
                aria-describedby={`${confirmationId}-hint`}
              />
              <p className="field-hint" id={`${confirmationId}-hint`}>
                계속하려면 &quot;{CONFIRMATION_PHRASE}&quot;를 그대로 입력해 주세요.
              </p>
            </div>

            {submitError !== null && (
              <p className="form-error" role="alert">
                {submitError}
              </p>
            )}

            <div className="flex justify-end gap-2">
              <Button variant="outline" type="button" disabled={submitting} onClick={closeConfirm}>
                취소
              </Button>
              <Button variant="danger" type="submit" loading={submitting}>
                {submitting ? '탈퇴하는 중…' : '계정을 영구히 삭제합니다'}
              </Button>
            </div>
          </form>
        )}
      </section>
    </div>
  )
}
