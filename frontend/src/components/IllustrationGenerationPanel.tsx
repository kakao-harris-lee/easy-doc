import { useEffect, useId, useRef, useState } from 'react'

import {
  ApiError,
  createIllustrationGenerationJob,
  downloadGeneratedIllustration,
  getGeneratedIllustrationImage,
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
import { formatCredits } from '../lib/credits'
import { Button } from './ui/Button'

const POLL_INTERVAL_MS = 2500

type Props = {
  conversionId: string
  contentRevision: number
  analysisVersion: string | null
  suggestions: IllustrationSuggestion[]
  disabled: boolean
  removeDisabled: boolean
}

function isActive(job: IllustrationGenerationJob | null): boolean {
  return job?.status === 'queued' || job?.status === 'running'
}

function AssetImage({
  conversionId,
  asset,
}: {
  conversionId: string
  asset: GeneratedIllustration
}) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    let objectUrl: string | null = null
    void getGeneratedIllustrationImage(conversionId, asset.asset_id, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => {
      controller.abort()
      if (objectUrl !== null) URL.revokeObjectURL(objectUrl)
    }
  }, [conversionId, asset.asset_id, attempt])
  if (failed)
    return (
      <div>
        <p>그림 미리보기를 불러오지 못했습니다.</p>
        <Button
          type="button"
          variant="outline"
          onClick={() => {
            setFailed(false)
            setAttempt((value) => value + 1)
          }}
        >
          미리보기 다시 불러오기
        </Button>
      </div>
    )
  if (url === null) return <p>그림을 불러오는 중…</p>
  return (
    <img
      src={url}
      alt={asset.alt_text}
      className="w-full max-w-lg rounded-lg border border-border"
    />
  )
}

export function IllustrationGenerationPanel({
  conversionId,
  contentRevision,
  analysisVersion,
  suggestions,
  disabled,
  removeDisabled,
}: Props) {
  const id = useId()
  const [collection, setCollection] = useState<IllustrationGenerationJobCollection | null>(null)
  const [job, setJob] = useState<IllustrationGenerationJob | null>(null)
  const [assets, setAssets] = useState<GeneratedIllustration[]>([])
  const [selected, setSelected] = useState('')
  const [reviewed, setReviewed] = useState<Record<string, boolean>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<CreateIllustrationGenerationJobRequest | null>(null)
  const [reload, setReload] = useState(0)
  const requestRef = useRef(false)
  const versionRef = useRef(0)

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
  const blocked =
    disabled ||
    busy ||
    isActive(job) ||
    collection?.enabled !== true ||
    collection.required_credits === null ||
    collection.required_credits > collection.available_credits ||
    !analysisVersion ||
    !suggestion?.configuration_revision

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
      if (caught instanceof ApiError && caught.status !== 0) setPending(null)
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
              <p className="text-sm">생성할 대체텍스트: {suggestion?.alt_text_draft}</p>
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
                    if (
                      blocked ||
                      !suggestion ||
                      !analysisVersion ||
                      !suggestion.configuration_revision
                    )
                      return
                    void submit({
                      request_id: crypto.randomUUID(),
                      expected_content_revision: contentRevision,
                      suggestion_id: suggestion.suggestion_id,
                      analysis_version: analysisVersion,
                      configuration_revision: suggestion.configuration_revision,
                      configuration: {
                        scenes: suggestion.scenes,
                        preserved_facts: suggestion.preserved_facts,
                        alt_text: suggestion.alt_text_draft,
                      },
                    })
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
          <AssetImage conversionId={conversionId} asset={asset} />
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
