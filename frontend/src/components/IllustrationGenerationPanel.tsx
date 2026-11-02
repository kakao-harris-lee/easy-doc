import { useEffect, useId, useRef, useState } from 'react'

import {
  ApiError,
  createIllustrationGenerationJob,
  downloadGeneratedIllustration,
  getGeneratedIllustrations,
  getIllustrationGenerationJob,
  listIllustrationGenerationJobs,
  updateGeneratedIllustrationApplication,
} from '../api/client'
import type {
  CreateIllustrationGenerationJobRequest,
  GeneratedIllustration,
  IllustrationGenerationJob,
  IllustrationGenerationJobCollection,
  IllustrationSuggestion,
} from '../api/types'
import { illustrationConfigurationRevision } from '../api/illustrationConfiguration'
import { formatCredits } from '../lib/credits'
import { Button } from './ui/Button'
import { GeneratedIllustrationImage } from './GeneratedIllustrationImage'

const POLL_INTERVAL_MS = 2500

type Props = {
  conversionId: string
  contentRevision: number
  analysisVersion: string | null
  suggestions: IllustrationSuggestion[]
  disabled: boolean
  removeDisabled: boolean
  onAssetsChange?: (assets: GeneratedIllustration[]) => void
}

function isActive(job: IllustrationGenerationJob | null): boolean {
  return job?.status === 'queued' || job?.status === 'running'
}

export function IllustrationGenerationPanel({
  conversionId,
  contentRevision,
  analysisVersion,
  suggestions,
  disabled,
  removeDisabled,
  onAssetsChange,
}: Props) {
  const id = useId()
  const [collection, setCollection] = useState<IllustrationGenerationJobCollection | null>(null)
  const [job, setJob] = useState<IllustrationGenerationJob | null>(null)
  const [assets, setAssets] = useState<GeneratedIllustration[]>([])
  const [selected, setSelected] = useState('')
  const [altTexts, setAltTexts] = useState<Record<string, string>>({})
  const [reviewed, setReviewed] = useState<Record<string, boolean>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<CreateIllustrationGenerationJobRequest | null>(null)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    onAssetsChange?.(assets)
  }, [assets, onAssetsChange])
  useEffect(() => () => onAssetsChange?.([]), [onAssetsChange])

  const requestRef = useRef(false)
  const versionRef = useRef(0)
  const submissionScopeRef = useRef({})
  useEffect(() => {
    submissionScopeRef.current = {}
    return () => {
      submissionScopeRef.current = {}
    }
  }, [disabled, analysisVersion, contentRevision, conversionId])

  useEffect(() => {
    const controller = new AbortController()
    const version = ++versionRef.current
    void listIllustrationGenerationJobs(conversionId, controller.signal)
      .then(async (next) => ({
        next,
        result: next.enabled
          ? await getGeneratedIllustrations(conversionId, controller.signal)
          : { assets: [] },
      }))
      .then(({ next, result }) => {
        if (controller.signal.aborted || version !== versionRef.current) return
        setCollection(next)
        setJob(next.active_job ?? next.latest_job)
        setAssets(result.assets)
        setError(null)
        setPending((current) =>
          current &&
          (next.active_job?.request_id === current.request_id ||
            next.latest_job?.request_id === current.request_id)
            ? null
            : current,
        )
        setReviewed({})
      })
      .catch(() => {
        if (!controller.signal.aborted && version === versionRef.current) {
          setCollection(null)
          setAssets([])
          setError('그림 생성 상태를 불러오지 못했습니다. 다시 확인해 주세요.')
        }
      })
    return () => controller.abort()
  }, [conversionId, contentRevision, reload])

  const currentJobId = isActive(job) ? job?.job_id : null
  useEffect(() => {
    if (!currentJobId) return
    const controller = new AbortController()
    let timer = 0
    const poll = async () => {
      const version = versionRef.current
      try {
        const next = await getIllustrationGenerationJob(
          conversionId,
          currentJobId,
          controller.signal,
        )
        if (controller.signal.aborted) return
        if (version !== versionRef.current) {
          timer = window.setTimeout(() => {
            void poll()
          }, POLL_INTERVAL_MS)
          return
        }
        if (!isActive(next)) {
          const [jobs, result] = await Promise.all([
            listIllustrationGenerationJobs(conversionId, controller.signal),
            getGeneratedIllustrations(conversionId, controller.signal),
          ])
          if (controller.signal.aborted) return
          if (version !== versionRef.current) {
            timer = window.setTimeout(() => {
              void poll()
            }, POLL_INTERVAL_MS)
            return
          }
          ++versionRef.current
          setCollection(jobs)
          setAssets(result.assets)
          const latest = jobs.active_job ?? jobs.latest_job
          setJob(latest && latest.job_id !== next.job_id ? latest : next)
          setError(null)
          return
        }
        ++versionRef.current
        setJob(next)
      } catch {
        if (controller.signal.aborted) return
        if (version !== versionRef.current) {
          timer = window.setTimeout(() => {
            void poll()
          }, POLL_INTERVAL_MS)
          return
        }
        setError('그림 생성 상태를 불러오지 못했습니다. 다시 확인하고 있습니다.')
      }
      if (!controller.signal.aborted)
        timer = window.setTimeout(() => {
          void poll()
        }, POLL_INTERVAL_MS)
    }
    timer = window.setTimeout(() => {
      void poll()
    }, POLL_INTERVAL_MS)
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [conversionId, currentJobId, reload])

  const suggestion = suggestions.find((item) => item.suggestion_id === selected) ?? suggestions[0]
  const altText = suggestion
    ? (altTexts[suggestion.suggestion_id] ?? suggestion.alt_text_draft)
    : ''
  const invalidAltText = altText.trim().length === 0 || altText.length > 300
  const blocked =
    invalidAltText ||
    disabled ||
    busy ||
    isActive(job) ||
    collection?.enabled !== true ||
    collection.required_credits === null ||
    collection.required_credits > collection.available_credits ||
    !analysisVersion ||
    !suggestion?.configuration_revision

  async function create() {
    if (blocked || !suggestion || !analysisVersion || requestRef.current) return
    requestRef.current = true
    setBusy(true)
    const scope = submissionScopeRef.current
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
      if (scope !== submissionScopeRef.current) return
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
      if (scope === submissionScopeRef.current)
        setError('그림 구성을 확인하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      requestRef.current = false
      setBusy(false)
    }
  }

  async function submit(body: CreateIllustrationGenerationJobRequest) {
    if (requestRef.current) return
    requestRef.current = true
    ++versionRef.current
    setBusy(true)
    setPending(body)
    setError(null)
    try {
      const created = await createIllustrationGenerationJob(conversionId, body)
      ++versionRef.current
      setJob(created.job)
      setPending(null)
      setCollection(
        (current) =>
          current && {
            ...current,
            active_job: isActive(created.job) ? created.job : null,
            latest_job: created.job,
            available_credits: created.creditBalance ?? current.available_credits,
          },
      )
      if (!isActive(created.job)) setReload((value) => value + 1)
    } catch (caught) {
      ++versionRef.current
      if (
        caught instanceof ApiError &&
        caught.status >= 400 &&
        caught.status < 500 &&
        caught.status !== 408
      )
        setPending(null)
      setError(
        caught instanceof ApiError &&
          caught.status === 409 &&
          caught.message === '문서당 그림 생성 요청 한도에 도달했습니다'
          ? '문서당 그림 생성 요청은 최대 10건입니다. 실패하거나 결과를 확인하지 못한 요청도 포함됩니다.'
          : caught instanceof ApiError && caught.status === 409
            ? '본문 또는 제안이 바뀌었거나 다른 작업이 진행 중입니다. 최신 상태를 확인해 주세요.'
            : caught instanceof ApiError && caught.status === 402
              ? '이용량이 부족합니다.'
              : '요청 결과를 확인하지 못했습니다. 같은 요청을 다시 보내거나 상태를 확인해 주세요.',
      )
    } finally {
      requestRef.current = false
      setBusy(false)
    }
  }

  async function changeApplication(asset: GeneratedIllustration, applied: boolean) {
    if (requestRef.current || (applied ? disabled || asset.stale : removeDisabled)) return
    requestRef.current = true
    ++versionRef.current
    setBusy(true)
    setError(null)
    try {
      const updated = await updateGeneratedIllustrationApplication(conversionId, asset.asset_id, {
        expected_content_revision: contentRevision,
        expected_asset_revision: asset.asset_revision,
        applied,
      })
      ++versionRef.current
      setAssets((current) =>
        current.map((item) => (item.asset_id === updated.asset_id ? updated : item)),
      )
      setReviewed((current) => ({ ...current, [asset.asset_id]: false }))
    } catch (caught) {
      ++versionRef.current
      if (caught instanceof ApiError && caught.status === 409) setAssets([])
      setError(
        caught instanceof ApiError && caught.status === 409
          ? '본문이나 그림 상태가 바뀌었습니다. 최신 상태를 확인해 주세요.'
          : '그림 적용 상태를 저장하지 못했습니다. 다시 확인해 주세요.',
      )
    } finally {
      requestRef.current = false
      setBusy(false)
    }
  }

  async function download(asset: GeneratedIllustration) {
    setError(null)
    try {
      const file = await downloadGeneratedIllustration(conversionId, asset.asset_id)
      const url = URL.createObjectURL(file.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = file.filename ?? 'illustration.png'
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch {
      setError('그림을 내려받지 못했습니다. 다시 시도해 주세요.')
    }
  }

  return (
    <section
      aria-labelledby={`${id}-heading`}
      className="space-y-3 rounded-lg border border-border p-4"
    >
      <h3 id={`${id}-heading`} className="font-semibold">
        그림 생성과 적용
      </h3>
      <Button
        type="button"
        variant="outline"
        disabled={busy}
        onClick={() => {
          ++versionRef.current
          setReload((value) => value + 1)
        }}
      >
        그림 상태 새로고침
      </Button>
      {error && <p role="alert">{error}</p>}
      {collection === null ? (
        <p>그림 생성 상태를 확인해 주세요.</p>
      ) : collection.enabled !== true ? (
        <p>현재 그림 생성을 사용할 수 없습니다.</p>
      ) : (
        <>
          <p>
            그림 한 장 필요 이용량:{' '}
            {collection.required_credits === null
              ? '설정되지 않음'
              : `${formatCredits(collection.required_credits)}크레딧`}{' '}
            / 남은 이용량 {formatCredits(collection.available_credits)}크레딧
          </p>
          {suggestions.length > 0 && (
            <>
              <label htmlFor={`${id}-suggestion`}>그림으로 만들 제안</label>
              <select
                id={`${id}-suggestion`}
                className="block max-w-full rounded-md border border-border bg-background p-2"
                value={suggestion?.suggestion_id ?? ''}
                onChange={(event) => setSelected(event.target.value)}
                disabled={busy || isActive(job) || pending !== null}
              >
                {suggestions.map((item, index) => (
                  <option key={item.suggestion_id} value={item.suggestion_id}>
                    제안 {index + 1} · {item.reason}
                  </option>
                ))}
              </select>
              <label htmlFor={id + '-alt-text'}>그림 대체텍스트</label>
              <textarea
                id={id + '-alt-text'}
                className="block min-h-20 w-full max-w-full rounded-md border border-border bg-background p-2"
                maxLength={300}
                value={altText}
                disabled={disabled || busy || isActive(job) || pending !== null}
                aria-describedby={id + '-alt-help'}
                onChange={(event) => {
                  if (suggestion)
                    setAltTexts((current) => ({
                      ...current,
                      [suggestion.suggestion_id]: event.target.value,
                    }))
                }}
              />
              <p id={id + '-alt-help'} className="text-sm">
                원문 근거에 맞게 1~300자로 확인·수정해 주세요. 생성한 뒤에는 수정할 수 없습니다.
              </p>
              {pending ? (
                <Button
                  type="button"
                  disabled={busy || disabled || isActive(job)}
                  onClick={() => {
                    void submit(pending)
                  }}
                >
                  같은 그림 요청 다시 보내기
                </Button>
              ) : (
                <Button
                  type="button"
                  disabled={blocked}
                  onClick={() => {
                    void create()
                  }}
                >
                  그림 만들기
                </Button>
              )}
            </>
          )}
        </>
      )}
      <div role="status">
        {isActive(job)
          ? '그림을 만들고 있습니다. 다른 화면으로 이동해도 계속됩니다.'
          : job?.status === 'failed'
            ? job.failure_code === 'outcome_unknown'
              ? '생성 결과를 확인하지 못했습니다. 자동으로 다시 생성하지 않습니다.'
              : '그림을 만들지 못했습니다. 자동으로 다시 생성하지 않습니다.'
            : job?.status === 'superseded'
              ? '본문이 바뀌어 생성 결과를 사용할 수 없습니다.'
              : job?.status === 'succeeded'
                ? '그림 생성이 완료되었습니다.'
                : ''}
      </div>
      {assets.map((asset) => (
        <article key={asset.asset_id} className="space-y-3 border-t border-border pt-3">
          <h4 className="font-semibold">그림 미리보기{asset.applied ? ' · 적용됨' : ''}</h4>
          {asset.provenance === 'fake' && (
            <p>테스트 그림입니다. 실제 AI가 생성한 그림이 아닙니다.</p>
          )}
          {asset.stale && (
            <p>이전 버전 본문으로 만든 그림입니다. 현재 본문에는 적용할 수 없습니다.</p>
          )}
          <GeneratedIllustrationImage conversionId={conversionId} asset={asset} />
          <p>대체텍스트: {asset.alt_text}</p>
          <p>원문 근거: {asset.source_anchors.map((anchor) => anchor.quote).join(' / ')}</p>
          {!asset.applied && (
            <label className="flex items-start gap-2">
              <input
                type="checkbox"
                checked={reviewed[asset.asset_id] ?? false}
                onChange={(event) =>
                  setReviewed((current) => ({ ...current, [asset.asset_id]: event.target.checked }))
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
                (asset.applied
                  ? removeDisabled
                  : disabled || asset.stale || !reviewed[asset.asset_id])
              }
              onClick={() => {
                void changeApplication(asset, !asset.applied)
              }}
            >
              {asset.applied ? '문서에서 그림 제거' : '문서에 그림 적용'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                void download(asset)
              }}
            >
              그림 다운로드
            </Button>
          </div>
        </article>
      ))}
    </section>
  )
}
