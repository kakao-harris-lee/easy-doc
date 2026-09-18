import { useId, useRef, useState } from 'react'
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronUp, Search } from 'lucide-react'

import {
  analyzeReviewSupport,
  ApiError,
  getReviewSupport,
  updateReviewSupportItem,
} from '../api/client'
import type {
  ReviewCoverageLimitation,
  ReviewItem,
  ReviewItemState,
  ReviewSupportResponse,
} from '../api/types'
import { Button } from './ui/Button'
import { Badge } from './ui/Badge'

interface ReviewSupportPanelProps {
  conversionId: string
  contentRevision: number
  dirty: boolean
  sourceAvailable: boolean
  mappingAvailable: boolean
  onNavigateSource: (indexes: number[], trigger: HTMLButtonElement) => void
}

const RELATION_LABELS: Array<{ matches: string[]; label: string; description: string }> = [
  {
    matches: ['amount', 'money', 'benefit'],
    label: '금액·적용 대상 확인',
    description: '금액과 그 금액이 적용되는 대상을 함께 확인해 주세요.',
  },
  {
    matches: ['target', 'subject', 'eligibility'],
    label: '대상 확인',
    description: '이 조건이 누구에게 적용되는지 원문과 비교해 주세요.',
  },
  {
    matches: ['all_or_one', 'and_or', 'logical', 'operator'],
    label: '모두·하나 확인',
    description: '조건을 모두 충족해야 하는지, 하나만 충족하면 되는지 확인해 주세요.',
  },
  {
    matches: ['exception', 'exclusion'],
    label: '예외 확인',
    description: '제외 대상과 예외 조건이 쉬운 글에도 남아 있는지 확인해 주세요.',
  },
  {
    matches: ['deadline', 'action', 'period'],
    label: '기한·행동 확인',
    description: '언제까지 무엇을 해야 하는지 원문과 비교해 주세요.',
  },
]

const LIMITATION_TEXT: Record<ReviewCoverageLimitation, string> = {
  mapping_unavailable: '쉬운 글의 정확한 비교 위치를 연결하지 못했습니다.',
  signal_limit: '검수 항목 일부만 표시됩니다. 문서 전체도 확인해 주세요.',
  ambiguous_source: '같은 표현이 반복되거나 원문 위치가 모호한 항목이 있습니다.',
}

function itemCopy(item: ReviewItem): { label: string; description: string } {
  if (item.kind === 'missing_fact') {
    return {
      label: '누락 의심',
      description: '원문에 있는 정보가 쉬운 글에도 남아 있는지 확인해 주세요.',
    }
  }
  const normalized = item.rule_code.toLowerCase()
  return (
    RELATION_LABELS.find(({ matches }) => matches.some((token) => normalized.includes(token))) ?? {
      label: '조건 확인',
      description: '원문의 조건 관계가 쉬운 글에 같은 뜻으로 남아 있는지 확인해 주세요.',
    }
  )
}

function stateLabel(state: ReviewItemState): string {
  if (state === 'confirmed') return '확인함'
  if (state === 'not_applicable') return '해당 없음'
  return '확인 필요'
}

export function ReviewSupportPanel({
  conversionId,
  contentRevision,
  dirty,
  sourceAvailable,
  mappingAvailable,
  onNavigateSource,
}: ReviewSupportPanelProps) {
  const headingId = useId()
  const openedOnce = useRef(false)
  const [open, setOpen] = useState(false)
  const [response, setResponse] = useState<ReviewSupportResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [savingItemId, setSavingItemId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [reasonItemId, setReasonItemId] = useState<string | null>(null)
  const [reasonDrafts, setReasonDrafts] = useState<Record<string, string>>({})

  const assessment = response?.assessment ?? null
  const stale =
    response?.status === 'stale' ||
    (assessment !== null && assessment.content_revision !== contentRevision)

  async function analyze(): Promise<void> {
    setLoading(true)
    setError(null)
    setNotice(null)
    try {
      const next = await analyzeReviewSupport(conversionId, {
        expected_content_revision: contentRevision,
      })
      setResponse(next)
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : '확인할 내용을 준비하지 못했습니다. 다시 시도해 주세요.',
      )
    } finally {
      setLoading(false)
    }
  }

  async function reloadAfterConflict(): Promise<void> {
    try {
      setResponse(await getReviewSupport(conversionId))
    } catch {
      // 충돌 안내가 더 구체적이다. 재조회까지 실패해도 사용자가 적던 사유는 그대로 둔다.
    }
  }

  async function updateItem(item: ReviewItem, state: ReviewItemState): Promise<void> {
    if (assessment === null || dirty || stale) return
    const reason = state === 'not_applicable' ? (reasonDrafts[item.item_id] ?? '').trim() : null
    if (state === 'not_applicable' && reason === '') {
      setReasonItemId(item.item_id)
      setError('해당 없음으로 저장하려면 이유를 적어 주세요.')
      return
    }

    setSavingItemId(item.item_id)
    setError(null)
    setNotice(null)
    try {
      const next = await updateReviewSupportItem(conversionId, item.item_id, {
        assessment_id: assessment.assessment_id,
        expected_content_revision: contentRevision,
        expected_review_revision: assessment.review_revision,
        state,
        reason,
      })
      setResponse(next)
      setReasonItemId(null)
      setNotice('검수 표시를 저장했습니다.')
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        setNotice('다른 화면에서 내용이나 검수 표시가 바뀌었습니다. 최신 상태를 불러왔습니다.')
        await reloadAfterConflict()
      } else {
        setError(
          caught instanceof ApiError
            ? caught.message
            : '검수 표시를 저장하지 못했습니다. 다시 시도해 주세요.',
        )
      }
    } finally {
      setSavingItemId(null)
    }
  }

  function toggleOpen(): void {
    const next = !open
    setOpen(next)
    if (next && !openedOnce.current) {
      openedOnce.current = true
      void analyze()
    }
  }

  const items = assessment?.items ?? []
  const missingItems = items.filter((item) => item.kind === 'missing_fact')
  const relationItems = items.filter((item) => item.kind === 'relation_check')
  const checkedCount = items.filter((item) => item.state !== 'needs_review').length

  return (
    <section
      className="mt-5 rounded-[12px] border border-border bg-card"
      aria-labelledby={headingId}
    >
      <button
        type="button"
        className="flex min-h-14 w-full items-center justify-between gap-3 px-4 py-3 text-left"
        aria-expanded={open}
        aria-controls={`${headingId}-content`}
        onClick={toggleOpen}
      >
        <span>
          <span className="block font-bold" id={headingId}>
            검수할 내용
          </span>
          <span className="mt-0.5 block text-sm text-muted-foreground">
            {assessment === null
              ? '원문의 조건과 누락 의심 항목을 확인합니다.'
              : `확인 필요 ${items.length - checkedCount}개 · 확인함 ${checkedCount}개`}
          </span>
        </span>
        {open ? (
          <ChevronUp className="size-5 shrink-0" aria-hidden="true" />
        ) : (
          <ChevronDown className="size-5 shrink-0" aria-hidden="true" />
        )}
      </button>

      {open && (
        <div id={`${headingId}-content`} className="border-t border-border px-4 py-4">
          <p className="text-sm text-muted-foreground">
            자동 검사는 확인할 대상을 찾는 보조 기능입니다. 표시된 항목을 모두 확인해도 모든 의미가
            보존됐다는 뜻은 아닙니다.
          </p>

          {dirty && (
            <p className="mt-3 rounded-[10px] border border-warning/25 bg-warning-surface p-3 text-sm text-warning">
              수정 중인 글입니다. 본문을 저장한 뒤 다시 확인해 주세요.
            </p>
          )}
          {stale && !dirty && (
            <div className="mt-3 rounded-[10px] border border-warning/25 bg-warning-surface p-3 text-sm text-warning">
              <p>본문이 바뀌었습니다. 확인할 내용을 다시 불러와 주세요.</p>
              <Button
                type="button"
                variant="outline"
                className="mt-3"
                onClick={() => void analyze()}
              >
                다시 분석
              </Button>
            </div>
          )}

          {loading && (
            <p className="mt-3" role="status">
              확인할 내용을 준비하고 있어요.
            </p>
          )}
          {error !== null && (
            <div className="form-error mt-3" role="alert">
              <p>{error}</p>
              {assessment === null && (
                <Button
                  type="button"
                  variant="outline"
                  className="mt-2"
                  onClick={() => void analyze()}
                >
                  다시 시도
                </Button>
              )}
            </div>
          )}
          {notice !== null && (
            <p className="mt-3 text-sm text-muted-foreground" role="status">
              {notice}
            </p>
          )}

          {assessment !== null && (
            <>
              {(assessment.coverage === 'limited' || !mappingAvailable) && (
                <div className="mt-3 rounded-[10px] border border-info/25 bg-info-surface p-3 text-sm text-info">
                  <p className="flex items-center gap-2 font-semibold">
                    <AlertTriangle className="size-4" aria-hidden="true" />
                    비교에 제한이 있습니다
                  </p>
                  <ul className="mt-2 list-disc pl-5">
                    {!mappingAvailable && (
                      <li>쉬운 글의 정확한 위치 대신 원문 전체에서 비교합니다.</li>
                    )}
                    {assessment.limitations.map((limitation) => (
                      <li key={limitation}>{LIMITATION_TEXT[limitation]}</li>
                    ))}
                  </ul>
                </div>
              )}

              <p className="mt-4 text-sm font-semibold">
                확인함 {checkedCount}/{items.length}{' '}
                <span className="font-normal text-muted-foreground">(표시된 검수 항목 기준)</span>
              </p>

              <ReviewItemGroup
                title="누락 의심"
                items={missingItems}
                empty="자동 검사에서 누락 의심 항목을 찾지 못했습니다. 조건과 예외는 직접 확인해 주세요."
                disabled={dirty || stale}
                savingItemId={savingItemId}
                reasonItemId={reasonItemId}
                reasonDrafts={reasonDrafts}
                sourceAvailable={sourceAvailable}
                onReasonItemId={setReasonItemId}
                onReasonChange={(itemId, value) =>
                  setReasonDrafts((current) => ({ ...current, [itemId]: value }))
                }
                onNavigateSource={onNavigateSource}
                onUpdate={updateItem}
              />
              <ReviewItemGroup
                title="조건 관계 확인"
                items={relationItems}
                empty="조건 관계 확인 항목을 불러오지 못했습니다. 문서 전체를 직접 비교해 주세요."
                disabled={dirty || stale}
                savingItemId={savingItemId}
                reasonItemId={reasonItemId}
                reasonDrafts={reasonDrafts}
                sourceAvailable={sourceAvailable}
                onReasonItemId={setReasonItemId}
                onReasonChange={(itemId, value) =>
                  setReasonDrafts((current) => ({ ...current, [itemId]: value }))
                }
                onNavigateSource={onNavigateSource}
                onUpdate={updateItem}
              />
            </>
          )}
        </div>
      )}
    </section>
  )
}

interface ReviewItemGroupProps {
  title: string
  items: ReviewItem[]
  empty: string
  disabled: boolean
  savingItemId: string | null
  reasonItemId: string | null
  reasonDrafts: Record<string, string>
  sourceAvailable: boolean
  onReasonItemId: (itemId: string | null) => void
  onReasonChange: (itemId: string, value: string) => void
  onNavigateSource: (indexes: number[], trigger: HTMLButtonElement) => void
  onUpdate: (item: ReviewItem, state: ReviewItemState) => Promise<void>
}

function ReviewItemGroup({
  title,
  items,
  empty,
  disabled,
  savingItemId,
  reasonItemId,
  reasonDrafts,
  sourceAvailable,
  onReasonItemId,
  onReasonChange,
  onNavigateSource,
  onUpdate,
}: ReviewItemGroupProps) {
  return (
    <section className="mt-5">
      <h3 className="text-base font-bold">{title}</h3>
      {items.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="mt-2 flex flex-col gap-3">
          {items.map((item) => {
            const copy = itemCopy(item)
            const indexes = Array.from(
              new Set(item.source_anchors.flatMap((anchor) => anchor.source_unit_indexes)),
            ).sort((a, b) => a - b)
            const quote = item.source_anchors.find((anchor) => anchor.quote.trim() !== '')?.quote
            const saving = savingItemId === item.item_id
            return (
              <li key={item.item_id} className="rounded-[10px] border border-border p-4">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <p className="font-semibold">{copy.label}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{copy.description}</p>
                  </div>
                  <Badge tone={item.state === 'needs_review' ? 'warning' : 'success'}>
                    {stateLabel(item.state)}
                  </Badge>
                </div>
                {quote !== undefined && (
                  <blockquote className="mt-3 border-l-2 border-border pl-3 text-sm text-muted-foreground">
                    {quote}
                  </blockquote>
                )}
                {indexes.length === 0 && (
                  <p className="mt-3 text-sm text-warning">비교 위치를 정확히 찾지 못했습니다.</p>
                )}
                {!sourceAvailable && (
                  <p className="mt-3 text-sm text-warning">원문을 불러온 뒤 직접 비교해 주세요.</p>
                )}
                {item.reason !== null && item.reason.trim() !== '' && (
                  <p className="mt-3 text-sm text-muted-foreground">저장한 사유: {item.reason}</p>
                )}

                {reasonItemId === item.item_id && (
                  <div className="mt-3">
                    <label
                      className="text-sm font-semibold"
                      htmlFor={`review-reason-${item.item_id}`}
                    >
                      해당하지 않는 이유
                    </label>
                    <textarea
                      id={`review-reason-${item.item_id}`}
                      className="mt-1 min-h-24 w-full rounded-[10px] border border-input bg-background px-3 py-2"
                      maxLength={500}
                      value={reasonDrafts[item.item_id] ?? item.reason ?? ''}
                      onChange={(event) => onReasonChange(item.item_id, event.target.value)}
                      disabled={disabled || saving}
                    />
                  </div>
                )}

                <div className="mt-3 flex flex-wrap gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    disabled={!sourceAvailable}
                    onClick={(event) => onNavigateSource(indexes, event.currentTarget)}
                  >
                    <Search className="size-4" aria-hidden="true" />
                    원문 보기
                  </Button>
                  {item.state === 'needs_review' ? (
                    <>
                      <Button
                        type="button"
                        variant="secondary"
                        loading={saving}
                        disabled={disabled}
                        onClick={() => void onUpdate(item, 'confirmed')}
                      >
                        {!saving && <CheckCircle2 className="size-4" aria-hidden="true" />}
                        확인했어요
                      </Button>
                      <Button
                        type="button"
                        variant="ghost"
                        disabled={disabled || saving}
                        onClick={() => onReasonItemId(item.item_id)}
                      >
                        해당 없음
                      </Button>
                    </>
                  ) : (
                    <Button
                      type="button"
                      variant="ghost"
                      loading={saving}
                      disabled={disabled}
                      onClick={() => void onUpdate(item, 'needs_review')}
                    >
                      다시 확인 필요
                    </Button>
                  )}
                  {reasonItemId === item.item_id && item.state === 'needs_review' && (
                    <Button
                      type="button"
                      variant="secondary"
                      loading={saving}
                      disabled={disabled}
                      onClick={() => void onUpdate(item, 'not_applicable')}
                    >
                      사유와 함께 저장
                    </Button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
