import { useEffect, useId, useRef, useState } from 'react'

import {
  ApiError,
  createActionGuideJob,
  downloadActionGuide,
  getActionGuide,
  getActionGuideJob,
  listActionGuideJobs,
  saveActionGuide,
} from '../api/client'
import type {
  ActionGuideContent,
  ActionGuideItem,
  ActionGuideJob,
  ActionGuideJobCollection,
  ActionGuideResource,
  ActionGuideSectionKind,
  CreateActionGuideJobRequest,
} from '../api/types'
import type { DocumentSource } from '../review/sourceText'
import { formatCredits } from '../lib/credits'
import { USAGE_PATH } from '../routes/paths'
import { Button } from './ui/Button'

export interface ActionGuidePanelProps {
  conversionId: string
  contentRevision: number
  bodyDirty: boolean
  bodyBusy: boolean
  bodyConflict: boolean
  source: DocumentSource
  onSaveBody: () => Promise<number | null>
  onDirtyChange?: (dirty: boolean) => void
  onReviewed?: () => void
}

const SECTIONS: readonly { kind: ActionGuideSectionKind; label: string }[] = [
  { kind: 'eligibility', label: '신청할 수 있는 사람' },
  { kind: 'benefits', label: '받을 수 있는 것' },
  { kind: 'documents', label: '준비할 서류' },
  { kind: 'steps', label: '신청 순서' },
  { kind: 'exceptions', label: '예외와 주의할 점' },
  { kind: 'contact', label: '문의할 곳' },
]

const EMPTY_ITEM: ActionGuideItem = { text: '', cautions: [], source_anchors: [] }

function errorText(caught: unknown, task: 'create' | 'save' | 'export' | 'load'): string {
  if (caught instanceof ApiError) {
    if (caught.status === 402) return '이용량이 부족합니다. 남은 이용량을 확인해 주세요.'
    if (caught.status === 429) return '이 문서의 안내문 생성 횟수를 모두 사용했습니다.'
    if (caught.status === 503) {
      return caught.retryAfterSeconds !== null
        ? `서버가 혼잡합니다. ${caught.retryAfterSeconds}초 후 다시 시도해 주세요. 요청이 접수되지 않았다면 이용량은 차감되지 않습니다.`
        : '서버가 혼잡합니다. 잠시 후 다시 시도해 주세요. 요청이 접수되지 않았다면 이용량은 차감되지 않습니다.'
    }
    if (caught.status === 409)
      return '다른 화면에서 본문이나 안내문이 바뀌었습니다. 현재 편집 내용은 유지됩니다. 최신 상태를 확인해 주세요.'
    return caught.message
  }
  if (task === 'create')
    return '요청 결과를 확인할 수 없습니다. 같은 작업을 조회한 뒤 다시 시도해 주세요.'
  if (task === 'save')
    return '안내문 저장 결과를 확인할 수 없습니다. 현재 편집 내용을 유지했습니다.'
  if (task === 'export') return '안내문 파일을 받지 못했습니다. 다시 시도해 주세요.'
  return '안내문 상태를 불러오지 못했습니다. 다시 시도해 주세요.'
}

function hasUnresolved(content: ActionGuideContent, source: DocumentSource): boolean {
  if (source.state.status !== 'ready') return true
  if (content.sections.length !== SECTIONS.length) return true
  const sourceLines = source.state.text.split(/\r?\n/)
  return content.sections.some((section) => {
    if (section.status === 'needs_review') return true
    if (section.status === 'not_in_source') return section.items.length > 0
    return (
      section.items.length === 0 ||
      section.items.some(
        (item) =>
          item.text.trim() === '' ||
          item.source_anchors.length === 0 ||
          item.source_anchors.some(
            (anchor) =>
              anchor.quote.trim() === '' ||
              anchor.source_unit_indexes.length === 0 ||
              anchor.source_unit_indexes.some(
                (index) => !sourceLines[index]?.includes(anchor.quote),
              ),
          ),
      )
    )
  })
}

export function ActionGuidePanel({
  conversionId,
  contentRevision,
  bodyDirty,
  bodyBusy,
  bodyConflict,
  source,
  onSaveBody,
  onDirtyChange,
  onReviewed,
}: ActionGuidePanelProps) {
  const headingId = useId()
  const [resource, setResource] = useState<ActionGuideResource | null>(null)
  const [jobs, setJobs] = useState<ActionGuideJobCollection | null>(null)
  const [job, setJob] = useState<ActionGuideJob | null>(null)
  const [draft, setDraft] = useState<ActionGuideContent | null>(null)
  const [dirty, setDirty] = useState(false)
  const [savedRevision, setSavedRevision] = useState<number | null>(null)
  const [confirmCreate, setConfirmCreate] = useState(false)
  const [bodySavedForConfirmation, setBodySavedForConfirmation] = useState(false)
  const [reviewChecked, setReviewChecked] = useState(false)
  const [guideConflict, setGuideConflict] = useState(false)
  const [pendingCreate, setPendingCreate] = useState<CreateActionGuideJobRequest | null>(null)
  const [unknownCreate, setUnknownCreate] = useState(false)
  const [requestChecked, setRequestChecked] = useState(false)
  const [dismissedJobId, setDismissedJobId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadedConversionId, setLoadedConversionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const dirtyRef = useRef(false)
  const editBaseRevisionRef = useRef<number | null>(null)
  const editSequenceRef = useRef(0)
  const saveLockedRef = useRef(false)
  const bodySavedRevisionRef = useRef<number | null>(null)
  const wasBodyDirtyRef = useRef(bodyDirty)
  const createTriggerRef = useRef<HTMLButtonElement>(null)
  const createConfirmRef = useRef<HTMLButtonElement>(null)
  const requestRef = useRef(false)
  const preparingRef = useRef(false)
  const revision = Math.max(contentRevision, savedRevision ?? 0)
  const guide = loadedConversionId === conversionId ? (resource?.guide ?? null) : null
  const stale =
    (loadedConversionId === conversionId && resource?.status === 'stale') ||
    (guide !== null && guide.based_on_content_revision !== revision)
  const activeJob =
    loadedConversionId === conversionId
      ? (jobs?.active_job ?? (job?.status === 'queued' || job?.status === 'running' ? job : null))
      : null
  const candidate =
    loadedConversionId === conversionId &&
    job?.status === 'succeeded' &&
    job.candidate_state === 'current' &&
    job.content !== null &&
    job.candidate_id !== null &&
    job.job_id !== dismissedJobId
      ? job
      : null
  const sourceLines = source.state.status === 'ready' ? source.state.text.split(/\r?\n/) : []
  const unresolved = draft === null || hasUnresolved(draft, source)
  const draftPlainText =
    draft === null
      ? ''
      : SECTIONS.map(({ kind, label }) => {
          const section = draft.sections.find((entry) => entry.kind === kind)
          if (section?.status === 'not_in_source') return `${label}\n원문에 안내 없음`
          const items =
            section?.items
              .map((item) =>
                [
                  item.text,
                  ...item.cautions.map((caution) => `주의: ${caution}`),
                  ...item.source_anchors.map(
                    (anchor) =>
                      `원문 ${anchor.source_unit_indexes.map((index) => index + 1).join(', ')}행: ${anchor.quote}`,
                  ),
                ].join('\n'),
              )
              .join('\n') ?? ''
          return `${label}\n${items}`
        }).join('\n\n')
  const canEdit =
    guide !== null && !stale && !bodyDirty && !bodyBusy && !bodyConflict && !guideConflict
  const isLoading = loading || loadedConversionId !== conversionId
  const insufficientCredits =
    (jobs !== null && jobs.available_credits < jobs.required_credits) ||
    error === '이용량이 부족합니다. 남은 이용량을 확인해 주세요.'

  useEffect(() => {
    if (bodyDirty && !wasBodyDirtyRef.current) {
      bodySavedRevisionRef.current = null
    }
    wasBodyDirtyRef.current = bodyDirty
  }, [bodyDirty])

  useEffect(() => {
    if (confirmCreate) createConfirmRef.current?.focus()
  }, [confirmCreate])

  useEffect(() => {
    let disposed = false
    const controller = new AbortController()
    async function restore() {
      try {
        const [nextResource, nextJobs] = await Promise.all([
          getActionGuide(conversionId, controller.signal),
          listActionGuideJobs(conversionId, controller.signal),
        ])
        if (disposed) return
        setError(null)
        setJob(null)
        setDirty(false)
        dirtyRef.current = false
        editBaseRevisionRef.current = null
        bodySavedRevisionRef.current = null
        setBodySavedForConfirmation(false)
        setGuideConflict(false)
        setReviewChecked(false)
        onDirtyChange?.(false)
        setSavedRevision(null)
        setDismissedJobId(null)
        setPendingCreate(null)
        setUnknownCreate(false)
        setRequestChecked(false)
        setResource(nextResource)
        setJobs(nextJobs)
        setDraft(nextResource.guide?.content ?? null)
        const latestId = nextResource.active_job_id ?? nextResource.latest_job_id
        if (latestId !== null) {
          try {
            const latest = await getActionGuideJob(conversionId, latestId, controller.signal)
            if (!disposed) setJob(latest)
          } catch (caught) {
            if (!disposed) setError(errorText(caught, 'load'))
          }
        }
      } catch (caught) {
        if (!disposed) {
          setResource(null)
          setJobs(null)
          setJob(null)
          setDraft(null)
          setDirty(false)
          dirtyRef.current = false
          editBaseRevisionRef.current = null
          bodySavedRevisionRef.current = null
          setGuideConflict(false)
          setReviewChecked(false)
          onDirtyChange?.(false)
          setError(errorText(caught, 'load'))
        }
      } finally {
        if (!disposed) {
          setLoadedConversionId(conversionId)
          setLoading(false)
        }
      }
    }
    void restore()
    return () => {
      disposed = true
      controller.abort()
    }
  }, [conversionId, onDirtyChange])

  useEffect(() => {
    const id = activeJob?.job_id
    if (id === undefined) return
    let disposed = false
    async function poll() {
      try {
        const next = await getActionGuideJob(conversionId, id!)
        if (disposed) return
        setJob(next)
        if (next.status !== 'queued' && next.status !== 'running') {
          const refreshed = await listActionGuideJobs(conversionId)
          if (!disposed) setJobs(refreshed)
        }
      } catch (caught) {
        if (!disposed) setError(errorText(caught, 'load'))
      }
    }
    const timer = window.setInterval(() => {
      void poll()
    }, 2500)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [conversionId, activeJob?.job_id])

  function edit(next: ActionGuideContent): void {
    if (saveLockedRef.current || guideConflict) return
    if (!dirtyRef.current) editBaseRevisionRef.current = guide?.guide_revision ?? null
    editSequenceRef.current += 1
    setDraft(next)
    setDirty(true)
    dirtyRef.current = true
    setReviewChecked(false)
    onDirtyChange?.(true)
    setNotice(null)
  }

  function editSection(
    kind: ActionGuideSectionKind,
    update: (
      section: ActionGuideContent['sections'][number],
    ) => ActionGuideContent['sections'][number],
  ): void {
    if (draft === null) return
    edit({
      ...draft,
      sections: draft.sections.map((section) =>
        section.kind === kind ? update(section) : section,
      ),
    })
  }

  async function refresh(): Promise<void> {
    setBusy(true)
    setError(null)
    try {
      const [nextResource, nextJobs] = await Promise.all([
        getActionGuide(conversionId),
        listActionGuideJobs(conversionId),
      ])
      setResource(nextResource)
      setJobs(nextJobs)
      if (!dirtyRef.current) {
        setDraft(nextResource.guide?.content ?? null)
      } else if (nextResource.guide?.guide_revision !== editBaseRevisionRef.current) {
        setGuideConflict(true)
      }
      setReviewChecked(false)
      const latestId = nextResource.active_job_id ?? nextResource.latest_job_id
      setJob(latestId === null ? null : await getActionGuideJob(conversionId, latestId))
      setNotice('최신 상태를 불러왔습니다. 저장되지 않은 편집 내용은 유지했습니다.')
    } catch (caught) {
      setError(errorText(caught, 'load'))
    } finally {
      setBusy(false)
    }
  }

  async function submitCreate(body: CreateActionGuideJobRequest): Promise<void> {
    if (requestRef.current) return
    requestRef.current = true
    setBusy(true)
    setError(null)
    setConfirmCreate(false)
    try {
      const created = await createActionGuideJob(conversionId, body)
      setJob(created.job)
      setJobs((current) =>
        current === null
          ? current
          : {
              ...current,
              active_job: created.job,
              latest_job: created.job,
              available_credits: created.creditBalance ?? current.available_credits,
            },
      )
      setUnknownCreate(false)
      setRequestChecked(false)
      setPendingCreate(null)
      setNotice('안내문 만들기 요청을 접수했습니다.')
    } catch (caught) {
      if (!(caught instanceof ApiError) || caught.status === 0) setUnknownCreate(true)
      else setPendingCreate(null)
      setRequestChecked(false)
      setError(errorText(caught, 'create'))
    } finally {
      requestRef.current = false
      setBusy(false)
    }
  }

  async function prepareCreate(): Promise<void> {
    if (busy || activeJob !== null || bodyConflict) return
    setError(null)
    setNotice(null)
    let expectedRevision = bodySavedRevisionRef.current ?? revision
    let expectedGuideRevision = resource?.guide?.guide_revision ?? null
    if (bodyDirty && bodySavedRevisionRef.current === null) {
      setBusy(true)
      try {
        const saved = await onSaveBody()
        if (saved === null) {
          setError('본문을 저장하지 못했습니다. 본문을 확인한 뒤 다시 시도해 주세요.')
          return
        }
        expectedRevision = saved
        bodySavedRevisionRef.current = saved
        setBodySavedForConfirmation(true)
        setSavedRevision(saved)
      } catch (caught) {
        setError(errorText(caught, 'save'))
        return
      } finally {
        setBusy(false)
      }
    }
    // 다른 탭에서 본문을 이미 저장했을 수도 있다. 생성 직전마다 서버 기준 이용량과
    // 안내문 revision을 확인하고, 표시했던 금액이 달라졌다면 새 금액을 다시 확인받는다.
    setBusy(true)
    try {
      const [refreshed, nextJobs] = await Promise.all([
        getActionGuide(conversionId),
        listActionGuideJobs(conversionId),
      ])
      setResource(refreshed)
      setJobs(nextJobs)
      expectedGuideRevision = refreshed.guide?.guide_revision ?? null
      if (!dirtyRef.current) setDraft(refreshed.guide?.content ?? null)
      setReviewChecked(false)
      if (nextJobs.required_credits !== jobs?.required_credits) {
        setNotice('필요 이용량이 바뀌었습니다. 새 금액을 확인한 뒤 다시 눌러 주세요.')
        return
      }
      if (nextJobs.available_credits < nextJobs.required_credits) {
        setError('이용량이 부족합니다. 남은 이용량을 확인해 주세요.')
        return
      }
    } catch {
      setError(
        bodySavedRevisionRef.current !== null
          ? '본문은 저장됐지만 최신 안내문 상태와 이용량을 확인하지 못했습니다. 생성 요청은 보내지 않았습니다. 다시 눌러 상태를 확인해 주세요.'
          : '최신 안내문 상태와 이용량을 확인하지 못했습니다. 생성 요청은 보내지 않았습니다. 다시 눌러 상태를 확인해 주세요.',
      )
      return
    } finally {
      setBusy(false)
    }
    const body = {
      request_id: crypto.randomUUID(),
      expected_content_revision: expectedRevision,
      expected_guide_revision: expectedGuideRevision,
    }
    setPendingCreate(body)
    await submitCreate(body)
  }

  async function startCreate(): Promise<void> {
    if (preparingRef.current) return
    preparingRef.current = true
    try {
      await prepareCreate()
    } finally {
      preparingRef.current = false
    }
  }

  function cancelConfirmation(): void {
    setConfirmCreate(false)
    bodySavedRevisionRef.current = null
    setBodySavedForConfirmation(false)
    window.setTimeout(() => createTriggerRef.current?.focus(), 0)
  }

  async function recoverRequest(): Promise<void> {
    if (pendingCreate === null) return
    setBusy(true)
    setError(null)
    try {
      const [nextResource, nextJobs] = await Promise.all([
        getActionGuide(conversionId),
        listActionGuideJobs(conversionId),
      ])
      setResource(nextResource)
      setJobs(nextJobs)
      let found = [nextJobs.active_job, nextJobs.latest_job].find(
        (entry) => entry?.request_id === pendingCreate.request_id,
      )
      if (!found) {
        for (const id of [nextResource.active_job_id, nextResource.latest_job_id]) {
          if (id === null) continue
          const checked = await getActionGuideJob(conversionId, id)
          if (checked.request_id === pendingCreate.request_id) {
            found = checked
            break
          }
        }
      }
      if (found) {
        setJob(await getActionGuideJob(conversionId, found.job_id))
        setUnknownCreate(false)
        setRequestChecked(false)
        setPendingCreate(null)
        setNotice('기존 요청의 상태를 찾았습니다.')
        return
      }
    } catch (caught) {
      setError(errorText(caught, 'load'))
      setBusy(false)
      return
    }
    setRequestChecked(true)
    setNotice(
      '접수된 작업을 찾지 못했습니다. 같은 요청 번호로 다시 보내려면 아래 버튼을 눌러 주세요.',
    )
    setBusy(false)
  }

  async function persist(
    markReviewed: boolean,
    candidateId: string | null = null,
    content = draft,
  ): Promise<void> {
    if (
      content === null ||
      busy ||
      saveLockedRef.current ||
      bodyDirty ||
      bodyBusy ||
      bodyConflict ||
      (stale && candidateId === null)
    )
      return
    if (
      guideConflict ||
      (dirtyRef.current && editBaseRevisionRef.current !== guide?.guide_revision)
    ) {
      setGuideConflict(true)
      setError(
        '최신 안내문과 편집 기준이 다릅니다. 현재 내용을 복사하거나 최신 안내문으로 다시 시작해 주세요.',
      )
      return
    }
    if (markReviewed && dirtyRef.current) {
      setError('먼저 안내문 초안을 저장한 뒤 담당자 확인을 저장해 주세요.')
      return
    }
    if (markReviewed && hasUnresolved(content, source)) {
      setError('원문 근거가 없거나 확인이 필요한 항목을 먼저 확인해 주세요.')
      return
    }
    if (markReviewed && !reviewChecked) {
      setError('원문과 비교하여 확인한 뒤 체크해 주세요.')
      return
    }
    const savingSequence = editSequenceRef.current
    saveLockedRef.current = true
    setBusy(true)
    setError(null)
    try {
      const next = await saveActionGuide(conversionId, {
        candidate_id: candidateId,
        expected_content_revision: revision,
        expected_guide_revision: dirtyRef.current
          ? editBaseRevisionRef.current
          : (guide?.guide_revision ?? null),
        content,
        mark_reviewed: markReviewed,
      })
      setResource(next)
      if (savingSequence === editSequenceRef.current) {
        setDraft(next.guide?.content ?? null)
        setDirty(false)
        dirtyRef.current = false
        editBaseRevisionRef.current = null
        onDirtyChange?.(false)
      }
      setGuideConflict(false)
      setReviewChecked(false)
      if (candidateId !== null && job !== null) setDismissedJobId(job.job_id)
      setNotice(markReviewed ? '담당자 확인을 저장했습니다.' : '안내문 초안을 저장했습니다.')
      if (markReviewed) onReviewed?.()
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) setGuideConflict(true)
      setError(errorText(caught, 'save'))
    } finally {
      saveLockedRef.current = false
      setBusy(false)
    }
  }

  async function copyCurrentDraft(): Promise<void> {
    try {
      await navigator.clipboard.writeText(draftPlainText)
      setNotice('현재 편집 내용을 복사했습니다.')
    } catch {
      setError('자동 복사에 실패했습니다. 아래 글상자의 내용을 선택해 복사해 주세요.')
    }
  }

  async function startFromLatest(): Promise<void> {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const latest = await getActionGuide(conversionId)
      setResource(latest)
      setDraft(latest.guide?.content ?? null)
      setDirty(false)
      dirtyRef.current = false
      editBaseRevisionRef.current = null
      setGuideConflict(false)
      setReviewChecked(false)
      onDirtyChange?.(false)
      setNotice('최신 안내문으로 다시 시작했습니다. 이전의 저장하지 않은 편집은 버렸습니다.')
    } catch (caught) {
      setError(errorText(caught, 'load'))
    } finally {
      setBusy(false)
    }
  }

  async function exportGuide(): Promise<void> {
    if (
      guide === null ||
      guide.status !== 'reviewed' ||
      stale ||
      dirty ||
      bodyDirty ||
      bodyBusy ||
      bodyConflict
    )
      return
    setBusy(true)
    setError(null)
    try {
      const downloaded = await downloadActionGuide(conversionId, guide.guide_revision)
      const url = URL.createObjectURL(downloaded.blob)
      const link = document.createElement('a')
      link.href = url
      link.download = downloaded.filename ?? 'action-guide.txt'
      document.body.append(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (caught) {
      setError(errorText(caught, 'export'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      aria-labelledby={headingId}
      className="space-y-5 rounded-xl border border-border bg-card p-4 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={headingId} className="text-lg font-semibold">
          행동 안내
        </h2>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => {
            void refresh()
          }}
        >
          상태 새로고침
        </Button>
      </div>
      {isLoading && <p role="status">안내문 상태를 불러오고 있어요.</p>}
      {!isLoading && error && (
        <p role="alert" className="rounded-md bg-danger/10 p-3 text-sm text-danger">
          {error}
        </p>
      )}
      {!isLoading && insufficientCredits && (
        <a
          className="inline-flex min-h-11 items-center font-semibold text-primary"
          href={USAGE_PATH}
        >
          이용량 화면으로 이동
        </a>
      )}
      {notice && (
        <p role="status" className="text-sm">
          {notice}
        </p>
      )}
      {bodyConflict && <p role="alert">본문 충돌을 해결한 뒤 안내문 작업을 계속해 주세요.</p>}
      {stale && (
        <p role="status" className="rounded-md bg-secondary p-3">
          이전 본문으로 만든 안내문입니다. 내용을 확인하거나 복사할 수 있지만 편집·내려받기는 할 수
          없습니다.
        </p>
      )}
      {guideConflict && (
        <div role="alert" className="space-y-3 rounded-md border border-danger p-4">
          <p>
            다른 화면에서 안내문이 바뀌었습니다. 현재 편집 내용은 보존했으며, 최신 안내문을 확인하기
            전에는 저장할 수 없습니다.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() => {
                void copyCurrentDraft()
              }}
            >
              현재 편집 내용 복사
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() => {
                void startFromLatest()
              }}
            >
              최신 안내문으로 다시 시작
            </Button>
          </div>
          <textarea
            aria-label="보존된 안내문 편집 내용"
            readOnly
            rows={6}
            className="w-full rounded-md border border-input bg-card p-2 text-sm"
            value={draftPlainText}
          />
        </div>
      )}
      {stale && draft !== null && (
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-md bg-secondary p-3 text-sm">
          {draftPlainText}
        </pre>
      )}
      <details className="rounded-md border border-border p-3">
        <summary className="cursor-pointer font-medium">원문 전체 보기</summary>
        {source.state.status === 'ready' ? (
          <pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap break-words text-sm">
            {sourceLines.map((line, index) => `${index + 1}. ${line}`).join('\n')}
          </pre>
        ) : (
          <p className="mt-3 text-sm">원문을 불러온 뒤 근거를 비교할 수 있습니다.</p>
        )}
      </details>

      {!isLoading && jobs !== null && (
        <div className="space-y-3 rounded-md border border-border p-4">
          <p>
            현재 저장된 본문으로 별도 안내문을 만듭니다. 본문은 바뀌지 않습니다. 원문에 안내가
            없으면 ‘원문에 안내 없음’으로 표시합니다.
          </p>
          <p id={`${headingId}-create-cost`} className="font-medium">
            필요 이용량 {formatCredits(jobs.required_credits)}크레딧 / 남은 이용량{' '}
            {formatCredits(jobs.available_credits)}크레딧
          </p>
          <p id={`${headingId}-create-policy`} className="text-sm text-muted-foreground">
            초안이 만들어지면 이용량이 사용됩니다. 초안을 적용하지 않아도 사용됩니다. 생성 실패 시
            예약한 이용량은 반환됩니다.
          </p>
          {activeJob !== null ? (
            <p role="status">안내문을 만들고 있어요. 다른 화면으로 이동해도 계속됩니다.</p>
          ) : unknownCreate ? (
            <div className="space-y-2">
              <p role="status">요청 결과를 확인할 수 없습니다. 기존 요청을 확인합니다.</p>
              <Button
                type="button"
                disabled={busy}
                onClick={() => {
                  void recoverRequest()
                }}
              >
                작업 상태 다시 확인
              </Button>
              {requestChecked && pendingCreate !== null && (
                <Button
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    void submitCreate(pendingCreate)
                  }}
                >
                  같은 요청 다시 보내기
                </Button>
              )}
            </div>
          ) : confirmCreate ? (
            <div
              role="group"
              aria-label="안내문 생성 확인"
              aria-describedby={`${headingId}-create-cost ${headingId}-create-policy`}
              className="flex flex-wrap items-center gap-2"
            >
              {bodyDirty && (
                <p className="w-full text-sm">
                  저장하지 않은 본문이 있습니다. 본문 저장 후 안내문을 만듭니다.
                </p>
              )}
              <Button
                ref={createConfirmRef}
                type="button"
                disabled={
                  busy || bodyBusy || bodyConflict || jobs.available_credits < jobs.required_credits
                }
                onClick={() => {
                  void startCreate()
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') cancelConfirmation()
                }}
              >
                {bodyDirty && !bodySavedForConfirmation
                  ? '본문 저장 후 만들기'
                  : `${formatCredits(jobs.required_credits)}크레딧으로 안내문 만들기`}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={cancelConfirmation}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') cancelConfirmation()
                }}
              >
                편집 계속하기
              </Button>
            </div>
          ) : (
            <Button
              ref={createTriggerRef}
              type="button"
              disabled={
                busy || bodyBusy || bodyConflict || jobs.available_credits < jobs.required_credits
              }
              onClick={() => {
                bodySavedRevisionRef.current = null
                setBodySavedForConfirmation(false)
                setConfirmCreate(true)
              }}
            >
              {guide === null ? '안내문 만들기' : '안내문 다시 만들기'}
            </Button>
          )}
        </div>
      )}

      {!isLoading && job?.status === 'failed' && (
        <p role="status">
          안내문을 만들지 못했습니다. 다시 만들기는 새 유료 요청입니다. 기존 안내문은 유지됩니다.
        </p>
      )}
      {!isLoading && job?.status === 'superseded' && (
        <p role="status">
          작업 중 본문이 바뀌어 이 후보는 적용할 수 없습니다. 다시 만들기를 선택해 주세요.
        </p>
      )}
      {!isLoading && job?.status === 'succeeded' && job.candidate_state === 'stale' && (
        <p role="status">
          이 후보는 이전 본문을 기준으로 만들어져 적용할 수 없습니다. 다시 만들기를 선택해 주세요.
        </p>
      )}
      {candidate !== null && (
        <section
          aria-labelledby={`${headingId}-candidate`}
          className="space-y-3 rounded-md border border-primary p-4"
        >
          <h3 id={`${headingId}-candidate`} className="font-semibold">
            행동 안내 보조자료 미리보기
          </h3>
          <p>
            기존 문서와 함께 읽는 보조자료입니다. 문서 전체의 내용을 담고 있지는 않습니다. 원문과
            비교하여 확인한 뒤 적용해 주세요. 현재 안내문은 자동으로 바뀌지 않습니다.
          </p>
          {dirty && (
            <p role="alert">
              현재 안내문의 저장하지 않은 편집 내용이 있습니다. 먼저 저장하거나 현재 안내문을 유지해
              주세요.
            </p>
          )}
          <div className="space-y-4 text-sm">
            {SECTIONS.map(({ kind, label }) => {
              const section = candidate.content?.sections.find((item) => item.kind === kind)
              return (
                <section key={kind} aria-label={label} className="space-y-2">
                  <h4 className="font-semibold">{label}</h4>
                  <p>
                    {section?.status === 'not_in_source'
                      ? '원문에 안내 없음'
                      : section?.status === 'available'
                        ? '원문 근거 있음 · 내용을 비교해 주세요'
                        : '확인 필요'}
                  </p>
                  {section?.status !== 'not_in_source' && (
                    <ul className="space-y-2">
                      {section?.items.map((item, index) => (
                        <li key={index} className="space-y-2 rounded-md bg-secondary p-3">
                          <p className="whitespace-pre-wrap">{item.text}</p>
                          {item.cautions.map((caution, cautionIndex) => (
                            <p key={cautionIndex} className="whitespace-pre-wrap">
                              <strong>주의:</strong> {caution}
                            </p>
                          ))}
                          {item.source_anchors.length === 0 ? (
                            <p>원문 근거 확인 필요</p>
                          ) : (
                            <details>
                              <summary className="cursor-pointer">
                                원문 근거 보기 ({item.source_anchors.length}개)
                              </summary>
                              {item.source_anchors.map((anchor, anchorIndex) => (
                                <blockquote
                                  key={anchorIndex}
                                  className="mt-2 whitespace-pre-wrap border-l-2 border-primary pl-3"
                                >
                                  원문{' '}
                                  {anchor.source_unit_indexes.map((line) => line + 1).join(', ')}행:{' '}
                                  {anchor.quote}
                                </blockquote>
                              ))}
                            </details>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )
            })}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              disabled={
                busy ||
                dirty ||
                bodyDirty ||
                bodyBusy ||
                bodyConflict ||
                guideConflict ||
                candidate.based_on_content_revision !== revision
              }
              onClick={() => {
                void persist(false, candidate.candidate_id, candidate.content)
              }}
            >
              {guide === null ? '초안 사용' : '후보 적용'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => setDismissedJobId(candidate.job_id)}
            >
              현재 안내문 유지
            </Button>
          </div>
        </section>
      )}

      {!isLoading && draft !== null && guide !== null && (
        <div className="space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm">
              {guide.status === 'reviewed' && !dirty
                ? '담당자 확인 완료'
                : 'AI 초안 · 원문과 비교해 확인해 주세요.'}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                disabled={!canEdit || !dirty || busy}
                onClick={() => {
                  void persist(false)
                }}
              >
                안내문 저장
              </Button>
              <Button
                type="button"
                disabled={
                  !canEdit ||
                  unresolved ||
                  dirty ||
                  !reviewChecked ||
                  busy ||
                  guide.status === 'reviewed'
                }
                onClick={() => {
                  void persist(true)
                }}
              >
                담당자 확인 저장
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={
                  guide.status !== 'reviewed' ||
                  stale ||
                  dirty ||
                  bodyDirty ||
                  bodyBusy ||
                  bodyConflict ||
                  busy
                }
                onClick={() => {
                  void exportGuide()
                }}
              >
                TXT로 내려받기
              </Button>
            </div>
          </div>
          {unresolved && (
            <p className="text-sm text-muted-foreground">
              확인이 필요한 항목이나 원문 근거가 없는 항목이 있습니다. 확인 저장 전에 해결해 주세요.
            </p>
          )}
          {SECTIONS.map(({ kind, label }) => {
            const section = draft.sections.find((entry) => entry.kind === kind)
            if (!section) return null
            return (
              <fieldset
                key={kind}
                disabled={!canEdit || busy}
                className="space-y-3 rounded-md border border-border p-4"
              >
                <legend className="px-1 font-semibold">{label}</legend>
                <label className="block text-sm">
                  안내 상태
                  <select
                    aria-label={`${label} 안내 상태`}
                    className="mt-1 block w-full rounded-md border border-input bg-card p-2"
                    value={section.status}
                    onChange={(event) =>
                      editSection(kind, (current) => ({
                        ...current,
                        status: event.target.value as typeof current.status,
                        items: event.target.value === 'not_in_source' ? [] : current.items,
                      }))
                    }
                  >
                    <option value="available">원문에서 확인됨</option>
                    <option value="needs_review">확인 필요</option>
                    <option value="not_in_source">원문에 안내 없음</option>
                  </select>
                </label>
                {section.status === 'not_in_source' ? (
                  <p>원문에 안내 없음</p>
                ) : (
                  section.items.map((item, index) => (
                    <div key={`${kind}-${index}`} className="space-y-2 rounded-md bg-secondary p-3">
                      <label className="block text-sm">
                        {index + 1}번 안내 내용
                        <textarea
                          aria-label={`${label} ${index + 1}번 내용`}
                          rows={2}
                          maxLength={500}
                          className="mt-1 w-full rounded-md border border-input bg-card p-2"
                          value={item.text}
                          onChange={(event) =>
                            editSection(kind, (current) => ({
                              ...current,
                              items: current.items.map((entry, at) =>
                                at === index ? { ...entry, text: event.target.value } : entry,
                              ),
                            }))
                          }
                        />
                      </label>
                      <label className="block text-sm">
                        주의할 점 (한 줄에 하나)
                        <textarea
                          aria-label={`${label} ${index + 1}번 주의할 점`}
                          rows={2}
                          className="mt-1 w-full rounded-md border border-input bg-card p-2"
                          value={item.cautions.join('\n')}
                          onChange={(event) =>
                            editSection(kind, (current) => ({
                              ...current,
                              items: current.items.map((entry, at) =>
                                at === index
                                  ? {
                                      ...entry,
                                      cautions: event.target.value.split('\n').filter(Boolean),
                                    }
                                  : entry,
                              ),
                            }))
                          }
                        />
                      </label>
                      <label className="block text-sm">
                        원문 근거
                        <select
                          aria-label={`${label} ${index + 1}번 원문 근거`}
                          className="mt-1 block w-full rounded-md border border-input bg-card p-2"
                          value={item.source_anchors[0]?.source_unit_indexes[0] ?? ''}
                          onChange={(event) =>
                            editSection(kind, (current) => ({
                              ...current,
                              items: current.items.map((entry, at) =>
                                at === index
                                  ? {
                                      ...entry,
                                      source_anchors:
                                        event.target.value === ''
                                          ? []
                                          : [
                                              {
                                                source_unit_indexes: [Number(event.target.value)],
                                                quote:
                                                  sourceLines[Number(event.target.value)]?.slice(
                                                    0,
                                                    1000,
                                                  ) ?? '',
                                              },
                                            ],
                                    }
                                  : entry,
                              ),
                            }))
                          }
                        >
                          <option value="">원문에서 선택해 주세요</option>
                          {sourceLines.map(
                            (line, lineIndex) =>
                              line.trim() !== '' && (
                                <option key={lineIndex} value={lineIndex}>
                                  {lineIndex + 1}행 · {line.slice(0, 80)}
                                </option>
                              ),
                          )}
                        </select>
                      </label>
                      {item.source_anchors.map((anchor, anchorIndex) => (
                        <blockquote
                          key={anchorIndex}
                          className="border-l-2 border-primary pl-3 text-sm"
                        >
                          원문 {anchor.source_unit_indexes.map((line) => line + 1).join(', ')}행:{' '}
                          {anchor.quote}
                        </blockquote>
                      ))}
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          editSection(kind, (current) => ({
                            ...current,
                            items: current.items.filter((_, at) => at !== index),
                            status: current.items.length === 1 ? 'needs_review' : current.status,
                          }))
                        }
                      >
                        항목 삭제
                      </Button>
                    </div>
                  ))
                )}
                {section.status !== 'not_in_source' && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() =>
                      editSection(kind, (current) => ({
                        ...current,
                        items: [...current.items, { ...EMPTY_ITEM }],
                        status: 'needs_review',
                      }))
                    }
                  >
                    항목 추가
                  </Button>
                )}
              </fieldset>
            )
          })}
          <label className="flex min-h-11 items-start gap-3 rounded-md border border-border p-3 text-sm">
            <input
              type="checkbox"
              className="mt-1 size-4"
              checked={reviewChecked}
              disabled={!canEdit || unresolved || dirty || busy || guide.status === 'reviewed'}
              onChange={(event) => setReviewChecked(event.target.checked)}
            />
            <span>원문과 비교하여 이 안내문의 내용을 확인했습니다.</span>
          </label>
          {stale && <p className="text-sm">이전 안내문 내용을 선택해 복사할 수 있습니다.</p>}
        </div>
      )}
    </section>
  )
}
