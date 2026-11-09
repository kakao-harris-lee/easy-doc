import { useEffect, useId, useRef, useState } from 'react'

import {
  ApiError,
  createIllustrationGenerationJob,
  downloadGeneratedIllustration,
  getGeneratedIllustrations,
  getIllustrationGenerationJob,
  listIllustrationGenerationJobs,
  updateGeneratedIllustrationApplication,
  updateGeneratedIllustrationReview,
} from '../api/client'
import {
  ILLUSTRATION_TEXT_MAX_LENGTH,
  illustrationConfigurationRevision,
  illustrationTextLength,
} from '../api/illustrationConfiguration'
import type {
  CreateIllustrationGenerationJobRequest,
  GeneratedIllustration,
  IllustrationGenerationFailureCode,
  IllustrationGenerationJob,
  IllustrationGenerationJobCollection,
  IllustrationSuggestion,
  IllustrationSuggestionPurpose,
} from '../api/types'
import { formatCredits } from '../lib/credits'
import { USAGE_PATH } from '../routes/paths'
import { GeneratedIllustrationImage } from './GeneratedIllustrationImage'
import { Button } from './ui/Button'

const POLL_INTERVAL_MS = 2500
const MAX_POLL_FAILURES = 3
const EMPTY_ASSETS: GeneratedIllustration[] = []

const PURPOSE_LABELS: Record<IllustrationSuggestionPurpose, string> = {
  procedure: '절차',
  comparison: '비교',
  relationship: '관계',
}

const FAILURE_TEXT: Record<IllustrationGenerationFailureCode, string> = {
  generation_failed: '이미지 제공 서비스가 그림을 만들지 못했습니다.',
  result_invalid: '만든 결과가 필요한 이미지 형식이나 내용 검사를 통과하지 못했습니다.',
  outcome_unknown: '생성 요청의 최종 결과를 확인하지 못했습니다.',
}

type Props = {
  conversionId: string
  contentRevision: number
  analysisVersion: string | null
  suggestions: IllustrationSuggestion[]
  disabled: boolean
  removeDisabled: boolean
  createEnabled?: boolean
  onAssetsChange?: (assets: GeneratedIllustration[]) => void
}

function isActive(job: IllustrationGenerationJob | null): boolean {
  return job?.status === 'queued' || job?.status === 'running'
}

function isRetryable(caught: unknown): boolean {
  return (
    caught instanceof ApiError &&
    (caught.status === 0 || caught.status === 408 || caught.status === 429 || caught.status >= 500)
  )
}

function loadError(caught: unknown): string {
  if (caught instanceof ApiError && caught.status === 401) {
    return '로그인이 만료되어 그림 상태 조회를 멈췄습니다. 다시 로그인한 뒤 확인해 주세요.'
  }
  if (caught instanceof ApiError && caught.status === 404) {
    return '문서나 작업을 더 이상 조회할 수 없어 그림 상태 확인을 멈췄습니다. 문서 상태를 확인해 주세요.'
  }
  return '그림 생성 상태를 불러오지 못했습니다. 상태 새로고침으로 다시 확인해 주세요.'
}

function bodyRangeLabel(start: number, end: number): string {
  return start === end ? `본문 ${start + 1}줄` : `본문 ${start + 1}–${end + 1}줄`
}

export function IllustrationGenerationPanel({
  conversionId,
  contentRevision,
  analysisVersion,
  suggestions,
  disabled,
  removeDisabled,
  createEnabled = true,
  onAssetsChange,
}: Props) {
  const id = useId()
  const scopeKey = `${conversionId}:${contentRevision}`
  const [collection, setCollection] = useState<IllustrationGenerationJobCollection | null>(null)
  const [job, setJob] = useState<IllustrationGenerationJob | null>(null)
  const [assets, setAssets] = useState<GeneratedIllustration[]>([])
  const [loadedScope, setLoadedScope] = useState<string | null>(null)
  const [selected, setSelected] = useState('')
  const [altTexts, setAltTexts] = useState<Record<string, string>>({})
  const [assetDrafts, setAssetDrafts] = useState<Record<string, string>>({})
  const [editingAssetId, setEditingAssetId] = useState<string | null>(null)
  const [reviewed, setReviewed] = useState<Record<string, boolean>>({})
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [showUsage, setShowUsage] = useState(false)
  const [pending, setPending] = useState<CreateIllustrationGenerationJobRequest | null>(null)
  const [reload, setReload] = useState(0)
  const requestRef = useRef(false)
  const versionRef = useRef(0)
  const configurationScopeRef = useRef({})
  const createControllerRef = useRef<AbortController | null>(null)
  const mutationControllerRef = useRef<AbortController | null>(null)
  const downloadControllerRef = useRef<AbortController | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const statusRef = useRef<HTMLParagraphElement>(null)
  const errorRef = useRef<HTMLParagraphElement>(null)
  const previousConversionRef = useRef(conversionId)
  const focusStatusRef = useRef(false)
  const focusErrorRef = useRef(false)

  const currentScope = loadedScope === scopeKey
  const currentCollection = currentScope ? collection : null
  const currentJob = currentScope ? job : null
  const visibleAssets = currentScope ? assets : EMPTY_ASSETS
  const suggestion = suggestions.find((item) => item.suggestion_id === selected) ?? suggestions[0]
  const altText = suggestion
    ? (altTexts[suggestion.suggestion_id] ?? suggestion.alt_text_draft)
    : ''
  const altTextLength = illustrationTextLength(altText)
  const invalidAltText = altText.trim().length === 0 || altTextLength > ILLUSTRATION_TEXT_MAX_LENGTH
  const canCreate = createEnabled && currentCollection?.enabled === true

  useEffect(() => {
    onAssetsChange?.(visibleAssets)
  }, [onAssetsChange, visibleAssets])
  useEffect(() => () => onAssetsChange?.([]), [onAssetsChange])
  useEffect(() => {
    configurationScopeRef.current = {}
  }, [disabled, analysisVersion, contentRevision, conversionId])

  useEffect(() => {
    const version = ++versionRef.current
    const controller = new AbortController()
    createControllerRef.current?.abort()
    mutationControllerRef.current?.abort()
    downloadControllerRef.current?.abort()
    requestRef.current = false
    if (previousConversionRef.current !== conversionId) {
      previousConversionRef.current = conversionId
      headingRef.current?.focus()
    }

    async function restore() {
      setBusy(false)
      setLoading(true)
      setError(null)
      setNotice(null)
      setShowUsage(false)
      setPending(null)
      setEditingAssetId(null)
      try {
        const [next, result] = await Promise.all([
          listIllustrationGenerationJobs(conversionId, controller.signal),
          getGeneratedIllustrations(conversionId, controller.signal),
        ])
        if (controller.signal.aborted || version !== versionRef.current) return
        const nextAssets = result.assets.filter((asset) => !asset.discarded)
        setCollection(next)
        setJob(next.active_job ?? next.latest_job)
        setAssets(nextAssets)
        setAssetDrafts(
          Object.fromEntries(nextAssets.map((asset) => [asset.asset_id, asset.alt_text])),
        )
        setReviewed({})
        setLoadedScope(scopeKey)
        setError(null)
      } catch (caught) {
        if (controller.signal.aborted || version !== versionRef.current) return
        setCollection(null)
        setJob(null)
        setAssets([])
        setLoadedScope(scopeKey)
        setError(loadError(caught))
      } finally {
        if (!controller.signal.aborted && version === versionRef.current) setLoading(false)
      }
    }
    void restore()
    return () => controller.abort()
  }, [conversionId, contentRevision, reload, scopeKey])

  const currentJobId = isActive(currentJob) ? currentJob!.job_id : null
  useEffect(() => {
    if (currentJobId === null || !currentScope) return
    const controller = new AbortController()
    const version = versionRef.current
    let timer = 0
    let failures = 0

    function schedule(delay: number) {
      timer = window.setTimeout(() => void poll(), delay)
    }

    async function poll(): Promise<void> {
      try {
        const next = await getIllustrationGenerationJob(
          conversionId,
          currentJobId!,
          controller.signal,
        )
        if (controller.signal.aborted || version !== versionRef.current) return
        failures = 0
        setError(null)
        if (isActive(next)) {
          setJob(next)
          schedule(POLL_INTERVAL_MS)
          return
        }
        try {
          const [jobs, result] = await Promise.all([
            listIllustrationGenerationJobs(conversionId, controller.signal),
            getGeneratedIllustrations(conversionId, controller.signal),
          ])
          if (controller.signal.aborted || version !== versionRef.current) return
          const nextAssets = result.assets.filter((asset) => !asset.discarded)
          setCollection(jobs)
          setAssets(nextAssets)
          setAssetDrafts(
            Object.fromEntries(nextAssets.map((asset) => [asset.asset_id, asset.alt_text])),
          )
          const latest = jobs.active_job ?? jobs.latest_job
          setJob(latest && latest.job_id !== next.job_id ? latest : next)
          setNotice('최신 그림 상태를 불러왔습니다.')
        } catch (caught) {
          if (controller.signal.aborted || version !== versionRef.current) return
          setJob(next)
          setError(loadError(caught))
        }
      } catch (caught) {
        if (controller.signal.aborted || version !== versionRef.current) return
        failures += 1
        if (!isRetryable(caught) || failures >= MAX_POLL_FAILURES) {
          setError(
            isRetryable(caught)
              ? '네트워크 또는 서버 오류가 계속되어 자동 상태 확인을 멈췄습니다. 서버 작업 취소나 이용량 반환을 뜻하지는 않습니다. 상태 새로고침으로 다시 확인해 주세요.'
              : loadError(caught),
          )
          return
        }
        setError('그림 상태를 일시적으로 확인하지 못했습니다. 잠시 후 자동으로 다시 확인합니다.')
        schedule(POLL_INTERVAL_MS * 2 ** (failures - 1))
      }
    }

    schedule(POLL_INTERVAL_MS)
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [conversionId, currentJobId, currentScope, reload])

  useEffect(() => {
    if (focusStatusRef.current && statusRef.current !== null) {
      focusStatusRef.current = false
      statusRef.current.focus()
    }
  }, [currentJob?.job_id])
  useEffect(() => {
    if (focusErrorRef.current && error !== null && errorRef.current !== null) {
      focusErrorRef.current = false
      errorRef.current.focus()
    }
  }, [error])
  useEffect(
    () => () => {
      createControllerRef.current?.abort()
      mutationControllerRef.current?.abort()
      downloadControllerRef.current?.abort()
    },
    [],
  )

  const blocked =
    invalidAltText ||
    disabled ||
    busy ||
    isActive(currentJob) ||
    !canCreate ||
    currentCollection === null ||
    currentCollection.required_credits === null ||
    currentCollection.required_credits > currentCollection.available_credits ||
    currentCollection.remaining_request_count <= 0 ||
    !analysisVersion ||
    !suggestion?.configuration_revision

  function showError(message: string) {
    focusErrorRef.current = true
    setError(message)
  }

  async function create() {
    if (blocked || !suggestion || !analysisVersion || requestRef.current) return
    const version = versionRef.current
    const configurationScope = configurationScopeRef.current
    requestRef.current = true
    setBusy(true)
    setLoading(false)
    const configuration = {
      scenes: suggestion.scenes,
      preserved_facts: suggestion.preserved_facts,
      alt_text: altText,
    }
    try {
      const revision =
        altText === suggestion.alt_text_draft
          ? suggestion.configuration_revision
          : await illustrationConfigurationRevision(configuration)
      if (version !== versionRef.current || configurationScope !== configurationScopeRef.current)
        return
      requestRef.current = false
      await submit({
        request_id: crypto.randomUUID(),
        expected_content_revision: contentRevision,
        suggestion_id: suggestion.suggestion_id,
        analysis_version: analysisVersion,
        configuration_revision: revision,
        configuration,
      })
    } catch {
      if (version === versionRef.current)
        showError('그림 구성을 확인하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      if (version === versionRef.current) {
        requestRef.current = false
        setBusy(false)
      }
    }
  }

  async function submit(body: CreateIllustrationGenerationJobRequest) {
    if (requestRef.current) return
    const version = ++versionRef.current
    const controller = new AbortController()
    createControllerRef.current = controller
    requestRef.current = true
    setBusy(true)
    setLoading(false)
    setPending(body)
    setError(null)
    setNotice(null)
    setShowUsage(false)
    try {
      const created = await createIllustrationGenerationJob(conversionId, body, controller.signal)
      if (controller.signal.aborted || version !== versionRef.current) return
      setJob(created.job)
      setPending(null)
      setCollection((current) =>
        current === null
          ? current
          : {
              ...current,
              active_job: isActive(created.job) ? created.job : null,
              latest_job: created.job,
              available_credits: created.creditBalance ?? current.available_credits,
            },
      )
      if (isActive(created.job)) focusStatusRef.current = true
      else setReload((value) => value + 1)
    } catch (caught) {
      if (controller.signal.aborted || version !== versionRef.current) return
      if (
        caught instanceof ApiError &&
        caught.status >= 400 &&
        caught.status < 500 &&
        caught.status !== 408
      ) {
        setPending(null)
      }
      if (caught instanceof ApiError && caught.status === 402) {
        const required = caught.creditsRequired ?? currentCollection?.required_credits
        const available = caught.creditBalance ?? currentCollection?.available_credits
        setShowUsage(true)
        showError(
          `이용량이 부족합니다.${required != null ? ` 필요 이용량 ${formatCredits(required)}크레딧,` : ''}${available != null ? ` 사용 가능 ${formatCredits(available)}크레딧입니다.` : ''} 기존 이용량을 확인하거나 충전해 주세요.`,
        )
      } else if (
        caught instanceof ApiError &&
        caught.status === 409 &&
        caught.message === '문서당 그림 생성 요청 한도에 도달했습니다'
      ) {
        showError(
          `문서당 그림 생성 요청은 최대 ${currentCollection?.request_limit ?? 10}건입니다. 실패하거나 결과를 확인하지 못한 요청과 재생성도 포함됩니다.`,
        )
      } else if (caught instanceof ApiError && caught.status === 409) {
        showError(
          '본문 또는 제안이 바뀌었거나 다른 작업이 진행 중입니다. 최신 상태를 확인해 주세요.',
        )
      } else {
        showError(
          '요청 결과를 확인하지 못했습니다. 상태를 새로 조회하거나 같은 요청 ID로 재전송해 주세요.',
        )
      }
    } finally {
      if (!controller.signal.aborted && version === versionRef.current) {
        requestRef.current = false
        setBusy(false)
      }
    }
  }

  async function mutateAsset(
    asset: GeneratedIllustration,
    action: 'apply' | 'remove' | 'review' | 'discard',
    draft?: string,
  ) {
    if (requestRef.current || isActive(currentJob)) return
    if (action === 'apply' && (disabled || asset.stale)) return
    if (action === 'remove' && removeDisabled) return
    const version = ++versionRef.current
    const controller = new AbortController()
    mutationControllerRef.current = controller
    requestRef.current = true
    setBusy(true)
    setLoading(false)
    setError(null)
    setNotice(null)
    try {
      const updated =
        action === 'apply' || action === 'remove'
          ? await updateGeneratedIllustrationApplication(
              conversionId,
              asset.asset_id,
              {
                expected_content_revision: contentRevision,
                expected_asset_revision: asset.asset_revision,
                applied: action === 'apply',
              },
              controller.signal,
            )
          : await updateGeneratedIllustrationReview(
              conversionId,
              asset.asset_id,
              {
                expected_content_revision: contentRevision,
                expected_asset_revision: asset.asset_revision,
                ...(action === 'review' ? { alt_text: draft } : { discarded: true as const }),
              },
              controller.signal,
            )
      if (controller.signal.aborted || version !== versionRef.current) return
      setAssets((current) =>
        action === 'discard' || updated.discarded
          ? current.filter((item) => item.asset_id !== asset.asset_id)
          : current.map((item) => (item.asset_id === updated.asset_id ? updated : item)),
      )
      setAssetDrafts((current) => ({ ...current, [asset.asset_id]: updated.alt_text }))
      setReviewed((current) => ({ ...current, [asset.asset_id]: false }))
      setEditingAssetId(null)
      setNotice(
        action === 'discard'
          ? '후보를 폐기했습니다. 목록과 문서 미리보기에서 제외했으며 이용량은 반환되지 않습니다.'
          : action === 'review'
            ? '대체텍스트를 저장했습니다. 생성 당시 구성 기록은 바뀌지 않습니다.'
            : '그림 적용 상태를 저장했습니다.',
      )
    } catch (caught) {
      if (controller.signal.aborted || version !== versionRef.current) return
      if (caught instanceof ApiError && caught.status === 409) {
        showError('본문이나 그림 상태가 바뀌었습니다. 최신 상태를 다시 불러옵니다.')
        setReload((value) => value + 1)
      } else {
        showError('그림 검토 내용을 저장하지 못했습니다. 다시 확인해 주세요.')
      }
    } finally {
      if (!controller.signal.aborted && version === versionRef.current) {
        requestRef.current = false
        setBusy(false)
      }
    }
  }

  function discard(asset: GeneratedIllustration) {
    if (asset.applied || asset.stale || busy) return
    if (window.confirm('이 후보를 폐기할까요? 되돌릴 수 없으며 이용량은 반환되지 않습니다.')) {
      void mutateAsset(asset, 'discard')
    }
  }

  async function download(asset: GeneratedIllustration) {
    const version = versionRef.current
    const controller = new AbortController()
    downloadControllerRef.current = controller
    setError(null)
    try {
      const file = await downloadGeneratedIllustration(
        conversionId,
        asset.asset_id,
        controller.signal,
      )
      if (controller.signal.aborted || version !== versionRef.current) return
      const url = URL.createObjectURL(file.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = file.filename ?? 'illustration.png'
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch {
      if (!controller.signal.aborted && version === versionRef.current) {
        showError('그림 다운로드에 실패했습니다. 다시 시도해 주세요.')
      }
    }
  }

  const editingDraft = editingAssetId === null ? '' : (assetDrafts[editingAssetId] ?? '')
  const editingLength = illustrationTextLength(editingDraft)
  const editingInvalid =
    editingDraft.trim().length === 0 || editingLength > ILLUSTRATION_TEXT_MAX_LENGTH

  return (
    <section
      aria-labelledby={`${id}-heading`}
      className="space-y-3 rounded-lg border border-border p-4"
    >
      <h3 id={`${id}-heading`} ref={headingRef} tabIndex={-1} className="font-semibold">
        그림 생성과 적용
      </h3>
      <Button
        type="button"
        variant="outline"
        disabled={busy}
        onClick={() => setReload((value) => value + 1)}
      >
        그림 상태 새로고침
      </Button>
      {error !== null && (
        <p id={`${id}-error`} ref={errorRef} tabIndex={-1} role="alert">
          {error}
        </p>
      )}
      {showUsage && (
        <a
          className="inline-flex min-h-11 items-center font-semibold text-primary"
          href={USAGE_PATH}
        >
          기존 이용량 확인·충전으로 이동
        </a>
      )}
      {loading || currentCollection === null ? (
        <p role="status">
          {loading ? '그림 생성 상태를 불러오는 중…' : '그림 생성 상태를 확인해 주세요.'}
        </p>
      ) : (
        <>
          <p>
            그림 한 장 필요 이용량:{' '}
            {currentCollection.required_credits === null
              ? '설정되지 않음'
              : `${formatCredits(currentCollection.required_credits)}크레딧`}{' '}
            / 남은 이용량 {formatCredits(currentCollection.available_credits)}크레딧
          </p>
          <p id={`${id}-request-limit`} className="text-sm">
            생성 요청 {currentCollection.request_count}/{currentCollection.request_limit}건 · 남은
            요청 {currentCollection.remaining_request_count}건. 실패·결과 불명확·본문 변경으로 끝난
            요청과 재생성도 포함됩니다.
          </p>
          {!canCreate && (
            <p>
              지금은 새 그림 생성이나 재생성을 요청할 수 없습니다. 기존 작업과 그림은 계속 확인할 수
              있습니다.
            </p>
          )}
          {canCreate && suggestions.length > 0 && (
            <>
              <label htmlFor={`${id}-suggestion`}>그림으로 만들 제안</label>
              <select
                id={`${id}-suggestion`}
                className="block max-w-full rounded-md border border-border bg-background p-2"
                value={suggestion?.suggestion_id ?? ''}
                onChange={(event) => setSelected(event.target.value)}
                disabled={busy || isActive(currentJob) || pending !== null}
                aria-describedby={`${id}-request-limit`}
              >
                {suggestions.map((item, index) => (
                  <option key={item.suggestion_id} value={item.suggestion_id}>
                    제안 {index + 1} · {item.reason}
                  </option>
                ))}
              </select>
              <label htmlFor={`${id}-alt-text`}>그림 대체텍스트</label>
              <textarea
                id={`${id}-alt-text`}
                className="block min-h-20 w-full max-w-full rounded-md border border-border bg-background p-2"
                value={altText}
                disabled={disabled || busy || isActive(currentJob) || pending !== null}
                aria-invalid={invalidAltText}
                aria-describedby={`${id}-alt-help ${id}-alt-count${invalidAltText ? ` ${id}-alt-error` : ''}`}
                onChange={(event) => {
                  if (suggestion)
                    setAltTexts((current) => ({
                      ...current,
                      [suggestion.suggestion_id]: event.target.value,
                    }))
                }}
              />
              <p id={`${id}-alt-help`} className="text-sm">
                원문 근거에 맞게 Unicode 문자 1~300자로 확인·수정해 주세요.
              </p>
              <p id={`${id}-alt-count`} className="text-sm">
                현재 {altTextLength}자 ·{' '}
                {invalidAltText && altTextLength > ILLUSTRATION_TEXT_MAX_LENGTH
                  ? `${altTextLength - ILLUSTRATION_TEXT_MAX_LENGTH}자 초과`
                  : `남은 ${ILLUSTRATION_TEXT_MAX_LENGTH - altTextLength}자`}
              </p>
              {invalidAltText && (
                <p id={`${id}-alt-error`} className="text-sm text-danger">
                  공백이 아닌 대체텍스트를 300자 이내로 입력해 주세요.
                </p>
              )}
              {pending !== null ? (
                <Button
                  type="button"
                  disabled={busy || disabled || isActive(currentJob)}
                  onClick={() => void submit(pending)}
                >
                  같은 그림 요청 재전송
                </Button>
              ) : (
                <Button type="button" disabled={blocked} onClick={() => void create()}>
                  그림 만들기
                </Button>
              )}
            </>
          )}
        </>
      )}
      <div role="status">
        {isActive(currentJob) ? (
          <p ref={statusRef} tabIndex={-1}>
            그림을 만들고 있습니다. 페이지를 나가도 서버 작업은 계속될 수 있습니다. 상태 조회가
            멈춰도 서버 작업 취소를 뜻하지는 않습니다.
          </p>
        ) : currentJob?.status === 'failed' ? (
          <p ref={statusRef} tabIndex={-1}>
            {FAILURE_TEXT[currentJob.failure_code ?? 'outcome_unknown']}{' '}
            {currentJob.reserved_credits > 0
              ? '서버 작업 상태에서 예약 이용량 반환이 확인됐습니다.'
              : '이 작업은 이용량을 예약하지 않았습니다.'}{' '}
            기존 본문과 다른 그림은 유지됩니다. 자동으로 다시 생성하지 않으며, 새 요청으로만
            재생성할 수 있습니다.
          </p>
        ) : currentJob?.status === 'superseded' ? (
          <p ref={statusRef} tabIndex={-1}>
            본문이 바뀌어 이 생성 결과를 사용할 수 없습니다.{' '}
            {currentJob.reserved_credits > 0
              ? '서버 작업 상태에서 예약 이용량 반환이 확인됐습니다.'
              : '이 작업은 이용량을 예약하지 않았습니다.'}{' '}
            최신 제안을 확인한 뒤 새 요청으로 다시 생성해 주세요.
          </p>
        ) : currentJob?.status === 'succeeded' ? (
          <p ref={statusRef} tabIndex={-1}>
            그림 생성이 완료되었습니다.
          </p>
        ) : null}
        {notice !== null && <p>{notice}</p>}
      </div>
      {visibleAssets.map((asset, index) => {
        const originalAvailable = suggestions.some(
          (item) => item.suggestion_id === asset.suggestion_id,
        )
        const editing = editingAssetId === asset.asset_id
        return (
          <article key={asset.asset_id} className="space-y-3 border-t border-border pt-3">
            <h4 className="font-semibold">
              그림 {index + 1} 미리보기{asset.applied ? ' · 적용됨' : ''}
            </h4>
            <p className="text-sm">원 제안 ID: {asset.suggestion_id}</p>
            {originalAvailable && (
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  const original = document.getElementById(
                    `illustration-suggestion-${asset.suggestion_id}`,
                  )
                  original?.focus()
                  original?.scrollIntoView({ block: 'center' })
                }}
              >
                원 제안으로 이동
              </Button>
            )}
            <p>제안 목적: {PURPOSE_LABELS[asset.purpose]}</p>
            <p>제안 이유: {asset.reason}</p>
            <p>{bodyRangeLabel(asset.body_range.start, asset.body_range.end)}</p>
            <div>
              <p className="font-medium">장면</p>
              <ul className="list-disc pl-5">
                {asset.scenes.map((scene, sceneIndex) => (
                  <li key={sceneIndex}>{scene}</li>
                ))}
              </ul>
            </div>
            {asset.preserved_facts.length > 0 && (
              <div>
                <p className="font-medium">보존할 사실</p>
                <ul className="list-disc pl-5">
                  {asset.preserved_facts.map((fact, factIndex) => (
                    <li key={factIndex}>{fact}</li>
                  ))}
                </ul>
              </div>
            )}
            <p>원문 근거: {asset.source_anchors.map((anchor) => anchor.quote).join(' / ')}</p>
            <p className="break-all text-sm text-muted-foreground">
              생성 구성 기록: {asset.configuration_revision}
            </p>
            {asset.provenance === 'fake' && (
              <p>테스트 그림입니다. 실제 AI가 생성한 그림이 아닙니다.</p>
            )}
            {asset.stale && (
              <p>
                이전 버전 본문으로 만든 그림입니다. 현재 본문에는 적용하거나 수정·폐기할 수
                없습니다.
              </p>
            )}
            <GeneratedIllustrationImage conversionId={conversionId} asset={asset} />
            {editing ? (
              <>
                <label htmlFor={`${id}-asset-alt-${asset.asset_id}`}>
                  그림 {index + 1} 대체텍스트 수정
                </label>
                <textarea
                  id={`${id}-asset-alt-${asset.asset_id}`}
                  className="block min-h-20 w-full max-w-full rounded-md border border-border bg-background p-2"
                  value={editingDraft}
                  disabled={busy || isActive(currentJob)}
                  aria-invalid={editingInvalid}
                  aria-describedby={`${id}-asset-alt-help-${asset.asset_id} ${id}-asset-alt-count-${asset.asset_id}${error !== null ? ` ${id}-error` : ''}`}
                  onChange={(event) =>
                    setAssetDrafts((current) => ({
                      ...current,
                      [asset.asset_id]: event.target.value,
                    }))
                  }
                />
                <p id={`${id}-asset-alt-help-${asset.asset_id}`} className="text-sm">
                  공백이 아닌 Unicode 문자 1~300자로 입력해 주세요.
                </p>
                <p id={`${id}-asset-alt-count-${asset.asset_id}`} className="text-sm">
                  현재 {editingLength}자 ·{' '}
                  {editingLength > ILLUSTRATION_TEXT_MAX_LENGTH
                    ? `${editingLength - ILLUSTRATION_TEXT_MAX_LENGTH}자 초과`
                    : `남은 ${ILLUSTRATION_TEXT_MAX_LENGTH - editingLength}자`}
                </p>
                {editingInvalid && (
                  <p className="text-sm text-danger">대체텍스트를 300자 이내로 입력해 주세요.</p>
                )}
                <Button
                  type="button"
                  disabled={
                    busy ||
                    isActive(currentJob) ||
                    editingInvalid ||
                    editingDraft === asset.alt_text
                  }
                  onClick={() => void mutateAsset(asset, 'review', editingDraft)}
                >
                  대체텍스트 저장
                </Button>
              </>
            ) : (
              <>
                <p>대체텍스트: {asset.alt_text}</p>
                {!asset.stale && (
                  <Button
                    type="button"
                    variant="outline"
                    disabled={busy || isActive(currentJob)}
                    onClick={() => {
                      setAssetDrafts((current) => ({
                        ...current,
                        [asset.asset_id]: asset.alt_text,
                      }))
                      setEditingAssetId(asset.asset_id)
                    }}
                  >
                    대체텍스트 수정
                  </Button>
                )}
              </>
            )}
            {!asset.applied && (
              <label className="flex items-start gap-2">
                <input
                  type="checkbox"
                  checked={reviewed[asset.asset_id] ?? false}
                  onChange={(event) =>
                    setReviewed((current) => ({
                      ...current,
                      [asset.asset_id]: event.target.checked,
                    }))
                  }
                />
                원문 근거와 그림 구성, 대체텍스트를 확인했습니다.
              </label>
            )}
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                disabled={
                  busy ||
                  isActive(currentJob) ||
                  (asset.applied
                    ? removeDisabled
                    : disabled || asset.stale || !reviewed[asset.asset_id])
                }
                onClick={() => void mutateAsset(asset, asset.applied ? 'remove' : 'apply')}
              >
                {asset.applied ? '문서에서 그림 제거' : '문서에 그림 적용'}
              </Button>
              {!asset.applied && !asset.stale && (
                <Button
                  type="button"
                  variant="outline"
                  disabled={busy || isActive(currentJob)}
                  onClick={() => discard(asset)}
                >
                  이 후보 폐기
                </Button>
              )}
              <Button type="button" variant="outline" onClick={() => void download(asset)}>
                그림 다운로드
              </Button>
            </div>
            <p className="text-sm text-muted-foreground">
              후보 폐기와 적용 해제는 이미 사용한 이용량을 반환하지 않습니다.
            </p>
          </article>
        )
      })}
    </section>
  )
}
