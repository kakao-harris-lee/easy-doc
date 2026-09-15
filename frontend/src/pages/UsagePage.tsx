import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { getWorkspaceUsage } from '../api/usage'
import type { WorkspaceCreditsResponse, WorkspaceUsageResponse } from '../api/types'
import { SubscriptionCard } from '../components/subscription/SubscriptionCard'
import { TargetPlanCatalog } from '../components/subscription/TargetPlanCatalog'
import { PageHeader } from '../components/PageHeader'
import { Button } from '../components/ui/Button'
import { HISTORY_PATH } from '../routes/paths'
import { useWorkspace } from '../workspace/context'

const CARD_CLASS = 'rounded-xl border border-border bg-card p-5'
const count = (value: number) => value.toLocaleString('ko-KR')
const usageDate = (value: string) =>
  new Date(value).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })

function RemainingUsage({ credits }: { credits: WorkspaceCreditsResponse }) {
  return (
    <div className="mt-5 border-t border-border pt-4">
      <dl>
        <dt className="text-sm text-muted-foreground">남은 이용량</dt>
        <dd className="mt-1 text-2xl font-bold tabular-nums text-foreground">
          {credits.enforced ? `${count(Math.max(0, credits.available))}크레딧` : '이용량 제한 없음'}
        </dd>
      </dl>
      {credits.enforced && credits.cycle_ends_at !== null && (
        <p className="mt-2 text-sm text-muted-foreground">
          제공량 {count(credits.allowance)}크레딧 ·{' '}
          {new Date(credits.cycle_ends_at).toLocaleDateString('ko-KR')} 이용 기간 종료
        </p>
      )}
      {credits.enforced && credits.cycle_started_at === null && (
        <p className="mt-2 text-sm text-muted-foreground">
          진행 중인 이용 기간이 없습니다. 플랜 결제가 완료되면 이용량이 제공됩니다.
        </p>
      )}
      {credits.enforced &&
        credits.cycle_started_at !== null &&
        credits.balance <= 0 &&
        credits.reserved === 0 && (
          <p className="mt-2 text-sm font-medium text-danger">
            이번 이용 기간의 제공량을 모두 사용했습니다.
          </p>
        )}
      {credits.enforced && credits.reserved > 0 && (
        <p className="mt-2 text-sm text-muted-foreground">
          변환 중인 {count(credits.reserved)}크레딧을 제외한 수량입니다.
        </p>
      )}
      {credits.signup_grant_skipped && (
        <p className="mt-2 text-sm text-muted-foreground">
          이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.
        </p>
      )}
    </div>
  )
}

// 작업 공간이 바뀌면 key로 조회 상태를 새로 만든다. 이전 공간의 지연 응답도 무시한다.
function WorkspaceUsage({ workspaceId }: { workspaceId: string }) {
  const [usage, setUsage] = useState<WorkspaceUsageResponse | null>(null)
  const [credits, setCredits] = useState<WorkspaceCreditsResponse | null>(null)
  const [usageError, setUsageError] = useState<string | null>(null)
  const [creditsError, setCreditsError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    getWorkspaceUsage(workspaceId, {}, controller.signal)
      .then((response) => {
        if (!controller.signal.aborted) setUsage(response)
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setUsageError(
            error instanceof ApiError ? error.message : '이용 기간 사용량을 불러오지 못했습니다.',
          )
        }
      })
    getWorkspaceCredits(workspaceId, controller.signal)
      .then((response) => {
        if (!controller.signal.aborted) setCredits(response)
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setCreditsError(
            error instanceof ApiError ? error.message : '남은 이용량을 불러오지 못했습니다.',
          )
        }
      })
    return () => controller.abort()
  }, [workspaceId])

  return (
    <section aria-labelledby="cycle-usage-heading" className={`order-2 ${CARD_CLASS}`}>
      <h2 id="cycle-usage-heading" className="text-sm font-semibold text-muted-foreground">
        현재 이용 기간 사용량
      </h2>
      {usageError !== null ? (
        <p role="alert" className="mt-3 text-sm text-danger">
          {usageError}
        </p>
      ) : usage === null || (credits === null && creditsError === null) ? (
        <p role="status" className="mt-3 text-sm text-muted-foreground">
          사용량을 불러오는 중입니다…
        </p>
      ) : credits !== null && credits.cycle_started_at === null ? (
        <div className="mt-3">
          <p className="text-lg font-semibold text-foreground">사용 중인 이용 기간이 없습니다.</p>
          <p className="mt-1 text-sm text-muted-foreground">
            미결제·결제 실패·만료 상태의 과거 사용량은 현재 사용량에 포함하지 않습니다.
          </p>
        </div>
      ) : (
        <>
          <p className="mt-3 text-3xl font-bold tabular-nums text-foreground">
            {count(usage.credits)}크레딧 사용
          </p>
          <p className="mt-2 text-sm text-muted-foreground">
            문서 {count(usage.documents)}건 · {count(usage.characters)}자
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {credits?.cycle_started_at !== null && credits?.cycle_started_at !== undefined
              ? `${usageDate(credits.cycle_started_at)} 이용 시작일부터 오늘까지`
              : '현재 이용 기간 시작일부터 오늘까지'}
          </p>
        </>
      )}
      {creditsError !== null ? (
        <p role="alert" className="mt-4 text-sm text-danger">
          {creditsError}
        </p>
      ) : credits === null ? (
        <p role="status" className="mt-4 text-sm text-muted-foreground">
          남은 이용량을 불러오는 중입니다…
        </p>
      ) : (
        <RemainingUsage credits={credits} />
      )}
    </section>
  )
}

export function UsagePage() {
  const { workspaces, currentId } = useWorkspace()
  const currentName = workspaces.find((workspace) => workspace.id === currentId)?.name
  const [reload, setReload] = useState(0)
  const [subscriptionReload, setSubscriptionReload] = useState(0)

  return (
    <section aria-labelledby="usage-heading" className="mx-auto max-w-5xl">
      <PageHeader
        context={currentName ?? '사용량'}
        title="플랜과 사용량"
        description="월 플랜을 선택하고 현재 이용 기간의 사용량과 남은 이용량을 확인한다."
        titleId="usage-heading"
      />
      <div className="grid gap-4 md:grid-cols-2">
        {currentId !== null && (
          <SubscriptionCard
            key={`subscription:${currentId}:${subscriptionReload}`}
            workspaceId={currentId}
            onChanged={() => setReload((value) => value + 1)}
          />
        )}
        {currentId === null ? (
          <>
            <p className={`order-1 ${CARD_CLASS}`}>작업 공간을 선택하면 사용량을 볼 수 있습니다.</p>
            <TargetPlanCatalog workspaceId={null} className="order-3 md:col-span-2" />
          </>
        ) : (
          <WorkspaceUsage key={`usage:${currentId}:${reload}`} workspaceId={currentId} />
        )}
      </div>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
        <p>1크레딧은 공백 포함 1,000자 분량입니다.</p>
        <div className="flex items-center gap-4">
          <Link
            to={HISTORY_PATH}
            className="inline-flex min-h-11 items-center text-primary hover:underline"
          >
            변환 기록 보기
          </Link>
          {currentId !== null && (
            <Button
              type="button"
              variant="ghost"
              className="min-h-11"
              onClick={() => {
                setReload((value) => value + 1)
                setSubscriptionReload((value) => value + 1)
              }}
            >
              새로고침
            </Button>
          )}
        </div>
      </div>
    </section>
  )
}
