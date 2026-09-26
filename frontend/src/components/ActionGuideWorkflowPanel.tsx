import { useCallback, useEffect, useId, useRef, useState } from 'react'

import {
  ApiError,
  applyGuideDraft,
  createActionGuideAnalysisJob,
  createGuideDraft,
  correctGuideAnalysis,
  downloadGuideDraft,
  getActionGuideWorkflow,
  getConversion,
  resolveGuideAnalysisSignal,
  reviewGuideAnalysis,
  reviewGuideDraft,
} from '../api/client'
import type {
  ActionGuideAnalysis,
  ActionGuideWorkflow,
  ApplyGuideDraftRequest,
  ConversionResponse,
  GuideAnalysisRevisionRequest,
  GuideDraft,
  GuideOutputMode,
  GuideReviewSignal,
} from '../api/types'
import { formatCredits } from '../lib/credits'
import { ActionGuideAnalysisView, ActionGuideEvidence } from './ActionGuideAnalysisView'
import { ActionGuideAnalysisCorrection } from './ActionGuideAnalysisCorrection'
import { ActionGuidePreviousBodies } from './ActionGuidePreviousBodies'
import { Button } from './ui/Button'

export interface ActionGuideWorkflowPanelProps {
  conversionId: string
  contentRevision: number
  savedBody: string
  bodyDirty: boolean
  bodyBusy: boolean
  bodyConflict: boolean
  onBodyApplying: (busy: boolean) => void
  onBodyApplied: (conversion: ConversionResponse) => void
  onBodyConflict: () => void
  onDirtyChange?: (dirty: boolean) => void
  onIntakeEnabledChange?: (enabled: boolean) => void
}

const MODE_LABELS: Record<GuideOutputMode, string> = {
  additional_guide: '단계별 추가 안내',
  full_document: '전체 문서 보완',
}

function revisions(analysis: ActionGuideAnalysis): GuideAnalysisRevisionRequest {
  return {
    expected_content_revision: analysis.based_on_content_revision,
    expected_analysis_revision: analysis.analysis_revision,
    expected_review_revision: analysis.review_revision ?? 0,
  }
}

function SignalReview({
  signal,
  analysis,
  disabled,
  onResolve,
  onDirty,
}: {
  signal: GuideReviewSignal
  analysis: ActionGuideAnalysis
  disabled: boolean
  onResolve: (
    signal: GuideReviewSignal,
    note: string,
    indexes: number[],
    quote: string | null,
  ) => Promise<void>
  onDirty: (id: string, dirty: boolean) => void
}) {
  const [note, setNote] = useState('')
  const [indexes, setIndexes] = useState<number[]>([])
  const [quote, setQuote] = useState('')
  const needsBody = signal.kind === 'source_body'
  useEffect(() => {
    onDirty(signal.id, !signal.resolved && Boolean(note || quote || indexes.length))
    return () => onDirty(signal.id, false)
  }, [signal.id, signal.resolved, note, quote, indexes.length, onDirty])
  return (
    <section aria-label={signal.detail} className="space-y-3 rounded-md border border-border p-3">
      <h4 className="font-semibold">
        {signal.resolved ? '검토 기록' : '확인 필요'}: {signal.detail}
      </h4>
      {signal.source_unit_ids.map((id) => (
        <blockquote key={id} className="whitespace-pre-wrap border-l-2 border-primary pl-3">
          원문 부분 {id + 1}:{' '}
          {analysis.source_units.find((unit) => unit.id === id)?.text ??
            '원문을 다시 확인해 주세요.'}
        </blockquote>
      ))}
      {signal.resolved ? (
        <>
          <p>{signal.resolution_note}</p>
          {signal.body_quote && (
            <blockquote className="whitespace-pre-wrap">
              현재 본문 근거: {signal.body_quote}
            </blockquote>
          )}
        </>
      ) : !signal.resolvable ? (
        <p>
          본문과 원문의 차이를 먼저 수정한 뒤 행동을 다시 확인해 주세요. 확인 표시만으로 해결할 수
          없습니다.
        </p>
      ) : (
        <fieldset disabled={disabled} className="space-y-3">
          {needsBody && (
            <>
              <legend className="font-medium">현재 본문에서 대응하는 부분 확인</legend>
              {(analysis.body_units ?? []).map((unit) => (
                <label key={unit.id} className="flex min-h-11 items-start gap-2 py-2">
                  <input
                    type="checkbox"
                    checked={indexes.includes(unit.id)}
                    onChange={(event) => {
                      const next = event.target.checked
                        ? [...indexes, unit.id].sort((a, b) => a - b)
                        : indexes.filter((id) => id !== unit.id)
                      setIndexes(next)
                      setQuote(
                        next.every((id, at) => at === 0 || id === (next[at - 1] ?? id) + 1)
                          ? next
                              .map(
                                (id) =>
                                  analysis.body_units?.find((part) => part.id === id)?.text ?? '',
                              )
                              .join('\n')
                          : '',
                      )
                    }}
                  />
                  <span className="whitespace-pre-wrap">
                    현재 본문 부분 {unit.id + 1}: {unit.text}
                  </span>
                </label>
              ))}
              <label className="block">
                현재 본문의 근거 문구
                <textarea
                  className="mt-1 w-full rounded-md border border-input p-2"
                  value={quote}
                  onChange={(event) => setQuote(event.target.value)}
                />
              </label>
            </>
          )}
          <label className="block">
            이 항목을 확인한 내용
            <textarea
              className="mt-1 w-full rounded-md border border-input p-2"
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
          </label>
          <Button
            type="button"
            className="min-h-11"
            disabled={!note.trim() || (needsBody && (indexes.length === 0 || !quote.trim()))}
            onClick={() => {
              void onResolve(signal, note, indexes, needsBody ? quote : null)
            }}
          >
            이 항목 검토 저장
          </Button>
        </fieldset>
      )}
      {!signal.resolved && Boolean(note || quote || indexes.length) && (
        <Button
          type="button"
          variant="outline"
          className="min-h-11"
          onClick={() => {
            setNote('')
            setQuote('')
            setIndexes([])
          }}
        >
          이 항목 입력 취소
        </Button>
      )}
    </section>
  )
}

export function ActionGuideWorkflowPanel(props: ActionGuideWorkflowPanelProps) {
  const {
    conversionId,
    contentRevision,
    savedBody,
    bodyDirty,
    bodyBusy,
    bodyConflict,
    onBodyApplying,
    onBodyApplied,
    onBodyConflict,
    onDirtyChange,
    onIntakeEnabledChange,
  } = props
  const headingId = useId()
  const [workflow, setWorkflow] = useState<ActionGuideWorkflow | null>(null)
  const [loadedId, setLoadedId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirmAnalysis, setConfirmAnalysis] = useState(false)
  const [analysisChecked, setAnalysisChecked] = useState(false)
  const [checkedBlocks, setCheckedBlocks] = useState<Record<string, string[]>>({})
  const [applyConfirmation, setApplyConfirmation] = useState<string | null>(null)
  const [correcting, setCorrecting] = useState(false)
  const [signalEdits, setSignalEdits] = useState<Record<string, boolean>>({})
  const [unknownAnalysisRequest, setUnknownAnalysisRequest] = useState<{
    request_id: string
    expected_content_revision: number
  } | null>(null)
  const applyRequests = useRef(new Map<string, ApplyGuideDraftRequest>())
  const draftRequests = useRef(new Map<string, string>())
  const requestLock = useRef(false)
  const mountedId = useRef(conversionId)
  const currentWorkflow = loadedId === conversionId ? workflow : null
  const analysis = currentWorkflow?.analysis ?? null
  const [confirmationBasis, setConfirmationBasis] = useState('')
  const nextConfirmationBasis = `${conversionId}:${contentRevision}:${analysis?.analysis_id}:${analysis?.analysis_revision}:${analysis?.review_revision}`
  if (confirmationBasis !== nextConfirmationBasis) {
    setConfirmationBasis(nextConfirmationBasis)
    setAnalysisChecked(false)
    setCheckedBlocks({})
    setApplyConfirmation(null)
  }
  const stale =
    analysis !== null &&
    (analysis.state === 'stale' || analysis.based_on_content_revision !== contentRevision)
  const blocked =
    busy || bodyDirty || bodyBusy || bodyConflict || stale || currentWorkflow?.active_job != null
  const formDirty = correcting || Object.values(signalEdits).some(Boolean)
  const hasSignals =
    analysis !== null &&
    ((analysis.signals ?? []).some((signal) => !signal.resolved) ||
      analysis.unresolved_signals.length > 0)
  const extractionBlocked =
    analysis !== null &&
    ((analysis.signals ?? []).some(
      (signal) =>
        !signal.resolved && signal.kind !== 'source_body' && signal.kind !== 'fact_difference',
    ) ||
      analysis.unresolved_signals.some(
        (id) => !(analysis.signals ?? []).some((signal) => signal.id === id),
      ))
  const markSignalDirty = useCallback(
    (id: string, dirty: boolean) =>
      setSignalEdits((current) => (current[id] === dirty ? current : { ...current, [id]: dirty })),
    [],
  )
  useEffect(() => {
    onDirtyChange?.(formDirty)
  }, [formDirty, onDirtyChange])

  useEffect(() => {
    mountedId.current = conversionId
    return () => {
      mountedId.current = ''
    }
  }, [conversionId])

  async function refresh(signal?: AbortSignal) {
    if (mountedId.current !== conversionId) return
    const next = await getActionGuideWorkflow(conversionId, signal)
    if (signal?.aborted || mountedId.current !== conversionId) return
    setWorkflow(next)
    setLoadedId(conversionId)
  }

  useEffect(() => {
    const controller = new AbortController()
    getActionGuideWorkflow(conversionId, controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return
        setError(null)
        setAnalysisChecked(false)
        setCheckedBlocks({})
        setApplyConfirmation(null)
        setUnknownAnalysisRequest(null)
        setConfirmAnalysis(false)
        setWorkflow(next)
        setLoadedId(conversionId)
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted)
          setError(caught instanceof Error ? caught.message : '행동 안내를 불러오지 못했습니다.')
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [conversionId, contentRevision])

  const activeJobId = currentWorkflow?.active_job?.job_id
  useEffect(() => {
    if (currentWorkflow) onIntakeEnabledChange?.(currentWorkflow.intake_enabled)
  }, [currentWorkflow, onIntakeEnabledChange])
  useEffect(() => {
    if (!activeJobId) return
    const controller = new AbortController()
    const timer = window.setInterval(() => {
      getActionGuideWorkflow(conversionId, controller.signal)
        .then((next) => {
          if (!controller.signal.aborted) setWorkflow(next)
        })
        .catch(() => {
          if (!controller.signal.aborted)
            setError('분석 상태를 불러오지 못했습니다. 상태를 다시 확인해 주세요.')
        })
    }, 2500)
    return () => {
      controller.abort()
      window.clearInterval(timer)
    }
  }, [conversionId, activeJobId])

  async function mutate(operation: () => Promise<void>) {
    if (requestLock.current || bodyDirty || bodyBusy || bodyConflict) return
    requestLock.current = true
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await operation()
    } catch (caught) {
      if (mountedId.current !== conversionId) return
      setError(
        caught instanceof ApiError && caught.status === 409
          ? '본문이나 분석이 다른 화면에서 바뀌었습니다. 현재 내용을 유지하고 최신 상태를 확인해 주세요.'
          : caught instanceof Error
            ? caught.message
            : '요청을 마치지 못했습니다. 상태를 다시 확인해 주세요.',
      )
    } finally {
      requestLock.current = false
      setBusy(false)
    }
  }

  function acceptAnalysis(next: ActionGuideAnalysis) {
    if (mountedId.current !== conversionId) return
    setWorkflow((current) => (current === null ? null : { ...current, analysis: next }))
    setAnalysisChecked(false)
    setCheckedBlocks({})
    setApplyConfirmation(null)
  }

  async function startAnalysis() {
    if (formDirty) return
    await mutate(async () => {
      if (!currentWorkflow?.intake_enabled || currentWorkflow.active_job) return
      const request = unknownAnalysisRequest ?? {
        request_id: crypto.randomUUID(),
        expected_content_revision: contentRevision,
      }
      setUnknownAnalysisRequest(request)
      const job = await createActionGuideAnalysisJob(conversionId, request).catch(
        (caught: unknown) => {
          if (mountedId.current !== conversionId) throw caught
          if (caught instanceof ApiError && caught.status < 500 && caught.status !== 408)
            setUnknownAnalysisRequest(null)
          throw caught
        },
      )
      if (mountedId.current !== conversionId) return
      setUnknownAnalysisRequest(null)
      setConfirmAnalysis(false)
      setWorkflow((current) =>
        current === null ? null : { ...current, active_job: job, latest_job: job },
      )
      await refresh()
    })
  }

  async function resolve(
    signal: GuideReviewSignal,
    note: string,
    indexes: number[],
    quote: string | null,
  ) {
    if (!analysis || stale) return
    await mutate(async () =>
      acceptAnalysis(
        await resolveGuideAnalysisSignal(conversionId, analysis.analysis_id, signal.id, {
          ...revisions(analysis),
          note,
          body_unit_indexes: indexes,
          body_quote: quote,
        }),
      ),
    )
  }

  async function compose(mode: GuideOutputMode) {
    if (
      !analysis ||
      formDirty ||
      (mode === 'full_document' && hasSignals) ||
      blocked ||
      !analysis.allowed_modes.includes(mode) ||
      !analysis.generation_enabled ||
      !currentWorkflow?.generation_enabled
    )
      return
    await mutate(async () => {
      const key = `${analysis.analysis_id}:${analysis.analysis_revision}:${analysis.review_revision}:${mode}`
      const requestId = draftRequests.current.get(key) ?? crypto.randomUUID()
      draftRequests.current.set(key, requestId)
      await createGuideDraft(conversionId, {
        ...revisions(analysis),
        request_id: requestId,
        analysis_id: analysis.analysis_id,
        mode,
      })
      if (mountedId.current !== conversionId) return
      await refresh()
      if (mountedId.current !== conversionId) return
      setNotice(`${MODE_LABELS[mode]} 초안을 저장했습니다. 내용을 비교해 확인해 주세요.`)
    })
  }

  async function apply(draft: GuideDraft) {
    if (
      !analysis ||
      blocked ||
      formDirty ||
      hasSignals ||
      draft.state !== 'current' ||
      !draft.reviewed
    )
      return
    await mutate(async () => {
      onBodyApplying(true)
      let applied = false
      try {
        const key = `${draft.draft_id}:${draft.draft_revision}`
        const request = applyRequests.current.get(key) ?? {
          ...revisions(analysis),
          request_id: crypto.randomUUID(),
          expected_draft_revision: draft.draft_revision,
        }
        applyRequests.current.set(key, request)
        await applyGuideDraft(conversionId, draft.draft_id, request)
        if (mountedId.current !== conversionId) return
        applied = true
        const latest = await getConversion(conversionId)
        if (mountedId.current !== conversionId) return
        if (latest.status !== 'done')
          throw new Error('반영 후 본문 상태를 확인할 수 없습니다. 최신 본문을 불러와 주세요.')
        onBodyApplied(latest)
        setApplyConfirmation(null)
        setNotice('전체 보완을 본문에 반영했습니다. 본문 검토와 확인 저장은 별도로 해 주세요.')
      } catch (caught) {
        if (mountedId.current !== conversionId) return
        if (applied || (caught instanceof ApiError && caught.status === 409)) onBodyConflict()
        throw caught
      } finally {
        onBodyApplying(false)
      }
    })
  }

  return (
    <section
      aria-labelledby={headingId}
      className="space-y-5 rounded-xl border border-border bg-card p-4 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold">
          행동 확인과 문서 보완
        </h2>
        <Button
          type="button"
          variant="outline"
          className="min-h-11"
          disabled={busy || formDirty}
          onClick={() => {
            void refresh().catch(() => setError('상태를 불러오지 못했습니다.'))
          }}
        >
          행동 분석 상태 새로고침
        </Button>
      </div>
      {loading && <p role="status">저장된 행동 안내를 불러오고 있습니다.</p>}
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      {bodyDirty && (
        <p role="status">저장하지 않은 본문 수정이 있습니다. 본문을 먼저 저장해 주세요.</p>
      )}
      {bodyConflict && <p role="alert">본문 충돌을 해결한 뒤 계속해 주세요.</p>}
      {stale && (
        <p role="status">
          이 분석은 이전 본문을 기준으로 합니다. 저장 자료는 읽을 수 있지만 새 본문으로 다시
          분석해야 적용할 수 있습니다.
        </p>
      )}
      {currentWorkflow && (
        <>
          <p>
            먼저 안내문인지 판단하고 원문에서 할 일을 찾습니다. 내용을 확인한 뒤 추가 안내를
            만들거나 전체 문서를 보완할 수 있습니다.
          </p>
          {!currentWorkflow.intake_enabled && (
            <p>새 행동 분석은 현재 사용할 수 없습니다. 저장된 자료는 계속 읽을 수 있습니다.</p>
          )}
          {currentWorkflow.active_job ? (
            <p role="status">
              원문과 본문의 행동을 분석하고 있습니다. 다른 화면에 다녀와도 이어서 확인할 수
              있습니다.
            </p>
          ) : (
            currentWorkflow.intake_enabled && (
              <div className="space-y-3">
                <p>
                  행동 분석 {formatCredits(currentWorkflow.required_credits)}크레딧 · 이후 안내 구성
                  추가 비용 {formatCredits(currentWorkflow.generation_credits)}크레딧 · 남은 이용량{' '}
                  {formatCredits(currentWorkflow.available_credits)}크레딧
                </p>
                <p className="text-sm">
                  분석 결과가 만들어지면 이용량이 사용됩니다. 분석 실패 시 예약한 이용량은
                  반환됩니다.
                </p>
                {unknownAnalysisRequest ? (
                  <>
                    <p role="status">
                      접수 결과를 확인하지 못했습니다. 같은 요청을 다시 확인해 주세요.
                    </p>
                    <Button
                      className="min-h-11"
                      disabled={busy || bodyDirty || bodyBusy || bodyConflict}
                      onClick={() => {
                        void startAnalysis()
                      }}
                    >
                      같은 행동 분석 요청 확인
                    </Button>
                  </>
                ) : confirmAnalysis ? (
                  <div
                    role="group"
                    aria-label="행동 분석 비용 확인"
                    className="flex flex-wrap gap-2"
                  >
                    <Button
                      className="min-h-11"
                      disabled={
                        busy ||
                        bodyDirty ||
                        bodyBusy ||
                        bodyConflict ||
                        currentWorkflow.available_credits < currentWorkflow.required_credits
                      }
                      onClick={() => {
                        void startAnalysis()
                      }}
                    >
                      {formatCredits(currentWorkflow.required_credits)}크레딧으로 행동 분석
                    </Button>
                    <Button
                      className="min-h-11"
                      variant="outline"
                      onClick={() => setConfirmAnalysis(false)}
                    >
                      취소
                    </Button>
                  </div>
                ) : (
                  <Button
                    className="min-h-11"
                    disabled={
                      busy ||
                      bodyDirty ||
                      bodyBusy ||
                      bodyConflict ||
                      currentWorkflow.available_credits < currentWorkflow.required_credits
                    }
                    onClick={() => setConfirmAnalysis(true)}
                  >
                    {analysis ? '행동 다시 확인' : '행동 확인'}
                  </Button>
                )}
              </div>
            )
          )}
          {currentWorkflow.latest_job?.status === 'failed' && (
            <p role="alert">행동 분석을 완료하지 못했습니다. 다시 분석하기는 새 요청입니다.</p>
          )}
        </>
      )}
      {analysis && (
        <>
          <ActionGuideAnalysisView analysis={analysis} />
          {correcting ? (
            <ActionGuideAnalysisCorrection
              key={`${analysis.analysis_id}:${analysis.analysis_revision}`}
              analysis={analysis}
              disabled={blocked}
              onCancel={() => setCorrecting(false)}
              onSave={async (body) => {
                await mutate(async () => {
                  const corrected = await correctGuideAnalysis(
                    conversionId,
                    analysis.analysis_id,
                    body,
                  )
                  if (mountedId.current !== conversionId) return
                  acceptAnalysis(corrected)
                  setCorrecting(false)
                  await refresh()
                })
              }}
            />
          ) : (
            <Button
              className="min-h-11"
              variant="outline"
              disabled={blocked || formDirty}
              onClick={() => setCorrecting(true)}
            >
              행동과 근거 수정
            </Button>
          )}
          {(analysis.signals ?? []).map((signal) => (
            <SignalReview
              key={`${analysis.analysis_id}:${analysis.analysis_revision}:${signal.id}`}
              signal={signal}
              analysis={analysis}
              disabled={blocked || correcting}
              onResolve={resolve}
              onDirty={markSignalDirty}
            />
          ))}
          {correcting && blocked && (
            <Button
              type="button"
              className="min-h-11"
              variant="outline"
              disabled={busy}
              onClick={() => setCorrecting(false)}
            >
              분석 수정 취소
            </Button>
          )}
          {analysis.unresolved_signals.length > 0 && (
            <p role="alert">
              해결하지 않은 확인 항목이 {analysis.unresolved_signals.length}개 있습니다. 원문과 현재
              본문을 대조해 주세요.
            </p>
          )}
          <div className="space-y-3">
            <label className="flex min-h-11 items-start gap-2 py-2">
              <input
                type="checkbox"
                checked={analysisChecked}
                disabled={blocked || formDirty || extractionBlocked || analysis.reviewed === true}
                onChange={(event) => setAnalysisChecked(event.target.checked)}
              />
              <span>이 분석의 행동·조건·미기재 정보를 원문과 대조했습니다.</span>
            </label>
            <Button
              className="min-h-11"
              disabled={
                blocked ||
                formDirty ||
                extractionBlocked ||
                !analysisChecked ||
                analysis.reviewed === true
              }
              onClick={() => {
                void mutate(async () =>
                  acceptAnalysis(
                    await reviewGuideAnalysis(
                      conversionId,
                      analysis.analysis_id,
                      revisions(analysis),
                    ),
                  ),
                )
              }}
            >
              {analysis.reviewed ? '행동 분석 검토 저장됨' : '행동 분석 검토 저장'}
            </Button>
          </div>
          <section aria-label="안내 구성 선택" className="space-y-3">
            <h3 className="font-semibold">어떤 안내가 필요한가요?</h3>
            <p>
              추가 안내는 문서와 함께 읽는 별도 자료입니다. 전체 보완은 현재 저장 본문을 유지하고
              확인한 행동 안내를 덧붙입니다.
            </p>
            {analysis.allowed_modes.length === 0 && (
              <p>현재 판단과 검토 상태에서는 안내를 구성할 수 없습니다.</p>
            )}
            {!currentWorkflow?.generation_enabled && <p>새 안내 구성은 현재 사용할 수 없습니다.</p>}
            <div className="flex flex-wrap gap-2">
              {analysis.allowed_modes.map((mode) => (
                <Button
                  key={mode}
                  className="min-h-11"
                  disabled={
                    blocked ||
                    formDirty ||
                    extractionBlocked ||
                    (mode === 'full_document' && hasSignals) ||
                    !analysis.reviewed ||
                    !analysis.generation_enabled ||
                    !currentWorkflow?.generation_enabled
                  }
                  onClick={() => {
                    void compose(mode)
                  }}
                >
                  {MODE_LABELS[mode]} 만들기
                </Button>
              ))}
            </div>
          </section>
        </>
      )}
      {currentWorkflow?.drafts.map((draft) => {
        const draftKey = `${draft.draft_id}:${draft.draft_revision}`
        const checked = checkedBlocks[draftKey] ?? []
        const current =
          draft.state === 'current' &&
          draft.based_on_content_revision === contentRevision &&
          draft.analysis_id === analysis?.analysis_id &&
          draft.analysis_revision === analysis.analysis_revision &&
          draft.analysis_review_revision === analysis.review_revision
        return (
          <section
            key={draft.draft_id}
            aria-label={`${MODE_LABELS[draft.mode]} 미리보기`}
            className="space-y-3 rounded-md border border-primary p-4"
          >
            <h3 className="font-semibold">{MODE_LABELS[draft.mode]} 미리보기</h3>
            {!current && (
              <p role="status">이전 버전의 저장 자료입니다. 현재 본문에 적용할 수 없습니다.</p>
            )}
            <p>
              {draft.mode === 'additional_guide'
                ? '별도 자료로 저장된 안내입니다. 전체 문서의 배경과 설명을 대신하지 않습니다.'
                : '현재 본문과 보완 내용을 비교해 주세요. 반영하면 저장된 본문이 바뀌며 확인 완료 상태는 해제됩니다.'}
            </p>
            {draft.mode === 'full_document' && (
              <div className="space-y-2">
                <h4 className="font-semibold">현재 저장 본문</h4>
                <p className="whitespace-pre-wrap">{savedBody}</p>
              </div>
            )}
            <h4 className="font-semibold">
              {draft.mode === 'full_document' ? '보완한 전체 본문' : '추가 안내 내용'}
            </h4>
            <p className="whitespace-pre-wrap">{draft.body}</p>
            <h4 className="font-semibold">추가한 행동 안내와 근거</h4>
            {draft.blocks.map((block) => (
              <section key={block.id} className="space-y-2 rounded-md bg-secondary p-3">
                <p className="whitespace-pre-wrap">{block.text}</p>
                {block.cautions.map((caution, index) => (
                  <p key={index} className="whitespace-pre-wrap">
                    주의: {caution}
                  </p>
                ))}
                <ActionGuideEvidence evidence={block.evidence} />
                <label className="flex min-h-11 items-center gap-2">
                  <input
                    type="checkbox"
                    disabled={blocked || !current || draft.reviewed}
                    checked={draft.reviewed || checked.includes(block.id)}
                    onChange={(event) =>
                      setCheckedBlocks((entries) => ({
                        ...entries,
                        [draftKey]: event.target.checked
                          ? [...checked, block.id]
                          : checked.filter((id) => id !== block.id),
                      }))
                    }
                  />
                  이 행동 안내와 조건을 확인했습니다.
                </label>
              </section>
            ))}
            <Button
              className="min-h-11"
              disabled={
                blocked ||
                !current ||
                draft.reviewed ||
                checked.length !== draft.blocks.length ||
                draft.blocks.length === 0
              }
              onClick={() => {
                if (!analysis) return
                void mutate(async () => {
                  await reviewGuideDraft(conversionId, draft.draft_id, {
                    ...revisions(analysis),
                    expected_draft_revision: draft.draft_revision,
                    confirmed_block_ids: checked,
                  })
                  await refresh()
                })
              }}
            >
              {draft.reviewed ? '안내 검토 저장됨' : '안내 검토 저장'}
            </Button>
            <Button
              className="min-h-11"
              variant="outline"
              disabled={blocked || formDirty || !current || !draft.reviewed}
              onClick={() => {
                void mutate(async () => {
                  const file = await downloadGuideDraft(conversionId, draft.draft_id)
                  const url = URL.createObjectURL(file.blob)
                  const link = document.createElement('a')
                  link.href = url
                  link.download = file.filename ?? `${draft.mode}.txt`
                  document.body.append(link)
                  link.click()
                  link.remove()
                  URL.revokeObjectURL(url)
                })
              }}
            >
              {MODE_LABELS[draft.mode]} TXT 내려받기
            </Button>
            {draft.mode === 'full_document' && (
              <div className="space-y-3">
                <label className="flex min-h-11 items-center gap-2">
                  <input
                    type="checkbox"
                    checked={applyConfirmation === draftKey}
                    disabled={blocked || formDirty || hasSignals || !current || !draft.reviewed}
                    onChange={(event) =>
                      setApplyConfirmation(event.target.checked ? draftKey : null)
                    }
                  />
                  이 보완본을 전체 본문에 반영하겠습니다. 이전 본문은 이력으로 남습니다.
                </label>
                <Button
                  className="min-h-11"
                  disabled={
                    blocked ||
                    formDirty ||
                    hasSignals ||
                    !current ||
                    !draft.reviewed ||
                    applyConfirmation !== draftKey
                  }
                  onClick={() => {
                    void apply(draft)
                  }}
                >
                  전체 본문에 반영
                </Button>
              </div>
            )}
          </section>
        )
      })}
      <ActionGuidePreviousBodies
        key={conversionId}
        conversionId={conversionId}
        contentRevision={contentRevision}
      />
    </section>
  )
}
