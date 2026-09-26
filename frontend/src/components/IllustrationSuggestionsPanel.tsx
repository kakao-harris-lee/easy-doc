import { useEffect, useId, useRef, useState } from 'react'

import {
  ApiError,
  createIllustrationSuggestionJob,
  getIllustrationSuggestionJob,
  getIllustrationSuggestions,
  listIllustrationSuggestionJobs,
} from '../api/client'
import type {
  CreateIllustrationSuggestionJobRequest,
  IllustrationSuggestion,
  IllustrationSuggestionFailureCode,
  IllustrationSuggestionJob,
  IllustrationSuggestionJobCollection,
  IllustrationSuggestionPurpose,
  IllustrationSuggestionsResource,
} from '../api/types'
import { formatCredits } from '../lib/credits'
import { USAGE_PATH } from '../routes/paths'
import { Button } from './ui/Button'
import { IllustrationTestFlow } from './IllustrationTestFlow'

export interface IllustrationSuggestionsPanelProps {
  conversionId: string
  contentRevision: number
  bodyDirty: boolean
  bodyBusy: boolean
  bodyConflict: boolean
  /** 저장된 본문. 제안이 가리키는 줄 범위를 이 본문에서 발췌한다. */
  savedBody: string
}

/** R2 행동 안내 작업과 같은 주기다 — 같은 lease 작업 큐를 읽는다. */
const POLL_INTERVAL_MS = 2500

const PURPOSE_LABELS: Record<IllustrationSuggestionPurpose, string> = {
  procedure: '절차',
  comparison: '비교',
  relationship: '관계',
}

/**
 * 실패 코드별 문구. 세 코드는 사용자에게 서로 다른 사실을 말한다 — 모델이 응답을 주지
 * 못한 것, 응답이 원문 근거와 맞지 않은 것, 결과 자체를 확인하지 못한 것이다.
 * 어느 쪽이든 예약한 이용량은 반환되므로(명세 §3) 차감 여부는 따로 적는다.
 */
const FAILURE_TEXTS: Record<IllustrationSuggestionFailureCode, string> = {
  generation_failed: '그림 제안을 만들지 못했습니다.',
  result_invalid: '분석 결과가 원문 근거와 맞지 않아 사용하지 않았습니다.',
  outcome_unknown: '분석 결과를 확인하지 못했습니다.',
}

function errorText(caught: unknown, task: 'create' | 'load'): string {
  // 조회는 사용자가 고를 수 있는 분기가 없다 — 상태 코드별로 다른 행동을 안내할 것이
  // 없으므로 한 문장으로 묶고, 접수만 402·409·429·503을 구분한다.
  if (task === 'load') return '그림 제안 상태를 불러오지 못했습니다. 다시 시도해 주세요.'
  if (caught instanceof ApiError) {
    if (caught.status === 402) return '이용량이 부족합니다. 남은 이용량을 확인해 주세요.'
    if (caught.status === 429) return '이 문서의 그림 제안 분석 횟수를 모두 사용했습니다.'
    if (caught.status === 503) {
      return caught.retryAfterSeconds !== null
        ? `서버가 혼잡합니다. ${caught.retryAfterSeconds}초 후 다시 시도해 주세요. 요청이 접수되지 않았다면 이용량은 차감되지 않습니다.`
        : '서버가 혼잡합니다. 잠시 후 다시 시도해 주세요. 요청이 접수되지 않았다면 이용량은 차감되지 않습니다.'
    }
    if (caught.status === 409)
      return '다른 화면에서 본문이 바뀌었거나 이미 분석 중인 작업이 있습니다. 최신 상태를 확인해 주세요.'
    return caught.message
  }
  return '요청 결과를 확인할 수 없습니다. 같은 요청 번호로 다시 보내거나 상태를 새로고침해 주세요.'
}

/** 1-based 본문 줄 범위 표기. 한 줄이면 범위를 적지 않는다. */
function bodyRangeLabel(start: number, end: number): string {
  return start === end ? `본문 ${start + 1}줄` : `본문 ${start + 1}–${end + 1}줄`
}

/** 제안 근거와 구성. 무료 테스트일 때만 별도 미리보기 흐름을 연다. */
function SuggestionCard({
  suggestion,
  index,
  bodyLines,
  disabledNoteId,
  testMode,
  testDisabled,
}: {
  suggestion: IllustrationSuggestion
  index: number
  bodyLines: readonly string[]
  disabledNoteId: string
  testMode: boolean
  testDisabled: boolean
}) {
  const headingId = useId()
  const excerpt = bodyLines.slice(suggestion.body_range.start, suggestion.body_range.end + 1)

  return (
    <li className="rounded-[10px] border border-border p-4">
      <section aria-labelledby={headingId} className="flex flex-col gap-3">
        <h3 id={headingId} className="text-base font-semibold">
          제안 {index + 1} · {PURPOSE_LABELS[suggestion.purpose]}
        </h3>
        <p className="text-sm text-foreground">{suggestion.reason}</p>

        <div>
          <p className="text-sm font-semibold">
            {bodyRangeLabel(suggestion.body_range.start, suggestion.body_range.end)}
          </p>
          {excerpt.length > 0 ? (
            <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md bg-secondary p-2 text-sm">
              {excerpt.join('\n')}
            </pre>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">
              저장된 본문에서 이 줄을 찾지 못했습니다.
            </p>
          )}
        </div>

        <div>
          <p className="text-sm font-semibold">원문 근거</p>
          <ul className="mt-1 flex flex-col gap-1">
            {suggestion.source_anchors.map((anchor, anchorIndex) => (
              <li key={anchorIndex} className="text-sm [overflow-wrap:anywhere]">
                원문 {anchor.source_unit_indexes.map((line) => line + 1).join(', ')}줄:{' '}
                {anchor.quote}
              </li>
            ))}
          </ul>
        </div>

        <div>
          <p className="text-sm font-semibold">그릴 내용</p>
          <ul className="mt-1 flex list-disc flex-col gap-1 pl-5">
            {suggestion.scenes.map((scene, sceneIndex) => (
              <li key={sceneIndex} className="text-sm">
                {scene}
              </li>
            ))}
          </ul>
        </div>

        {suggestion.preserved_facts.length > 0 && (
          <div>
            <p className="text-sm font-semibold">그림이 바꾸면 안 되는 내용</p>
            <ul className="mt-1 flex list-disc flex-col gap-1 pl-5">
              {suggestion.preserved_facts.map((fact, factIndex) => (
                <li key={factIndex} className="text-sm">
                  {fact}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div>
          <p className="text-sm font-semibold">대체텍스트 초안</p>
          <p className="mt-1 text-sm">{suggestion.alt_text_draft}</p>
        </div>

        {testMode ? (
          <IllustrationTestFlow
            suggestion={suggestion}
            excerpt={excerpt.join('\n')}
            disabled={testDisabled}
          />
        ) : (
          <div>
            {/*
            ER-18 이전에는 누를 수 없다. 버튼을 숨기지 않는 이유는 다음 단계가 무엇인지
            화면에서 읽히게 하기 위해서다.

            `disabled`가 아니라 `aria-disabled`를 쓴다 — `disabled` 버튼은 초점을 받지
            못해 낭독기 사용자가 «왜 못 누르는지»를 적어 둔 설명(`aria-describedby`)에
            영영 닿지 못한다. 보이는 모양은 그대로 두고, 눌러도 아무 일도 하지 않는다.
          */}
            <Button
              type="button"
              aria-disabled="true"
              aria-describedby={disabledNoteId}
              className="cursor-not-allowed opacity-50"
              onClick={(event) => event.preventDefault()}
            >
              이 내용으로 그림 만들기
            </Button>
          </div>
        )}
      </section>
    </li>
  )
}

/**
 * R7 ER-17 — 저장된 본문에서 그림으로 설명하면 도움이 될 문맥을 **텍스트 제안**으로만
 * 보여 준다. 이 화면은 이미지를 만들지 않고(ER-18), 분석을 자동으로 요청하지도 않는다
 * (명세 §1). 제안은 요청 → 작업 폴링 → 결과 조회의 세 단계로 나뉘며 R2 행동 안내와
 * 같은 작업 패턴을 쓴다.
 */
export function IllustrationSuggestionsPanel({
  conversionId,
  contentRevision,
  bodyDirty,
  bodyBusy,
  bodyConflict,
  savedBody,
}: IllustrationSuggestionsPanelProps) {
  const headingId = useId()
  const generationNoteId = useId()
  const costId = useId()
  const [resource, setResource] = useState<IllustrationSuggestionsResource | null>(null)
  const [jobs, setJobs] = useState<IllustrationSuggestionJobCollection | null>(null)
  const [job, setJob] = useState<IllustrationSuggestionJob | null>(null)
  // 완료 상태를 표시해도 결과 조회가 끝나기 전에는 폴링 effect를 정리하지 않는다.
  const [pollingJobId, setPollingJobId] = useState<string | null>(null)
  const [pendingCreate, setPendingCreate] = useState<CreateIllustrationSuggestionJobRequest | null>(
    null,
  )
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadedConversionId, setLoadedConversionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const requestRef = useRef(false)
  const refreshControllerRef = useRef<AbortController | null>(null)
  const activeStatusRef = useRef<HTMLParagraphElement>(null)
  const focusActiveStatusRef = useRef(false)

  const loaded = loadedConversionId === conversionId
  const latestJob = loaded ? (jobs?.latest_job ?? null) : null
  /**
   * 이 화면이 아는 **가장 최신** 작업. 폴링이 방금 받은 `job`이 목록 응답보다 항상 새롭다
   * — 목록은 그 뒤에 다시 읽으므로 잠시 한 틱 뒤처진다. 문서가 바뀌는 동안(`!loaded`)에는
   * 이전 문서의 작업을 새 문서의 것으로 읽지 않도록 둘 다 버린다.
   */
  const currentJob = (loaded ? job : null) ?? latestJob
  /**
   * 진행 중 작업. `currentJob`을 먼저 보고, 그것이 없을 때만 목록의 `active_job`으로
   * 되돌아간다. 순서를 뒤집으면 폴링이 실패를 받은 직후 아직 갱신되지 않은 목록의
   * queued 작업이 이겨 「분석하고 있어요」와 실패 문구가 같이 뜨고 같이 낭독된다.
   */
  const activeJob =
    currentJob !== null
      ? currentJob.status === 'queued' || currentJob.status === 'running'
        ? currentJob
        : null
      : loaded
        ? (jobs?.active_job ?? null)
        : null
  const suggestions = loaded ? (resource?.suggestions ?? []) : []
  const status = loaded ? (resource?.status ?? null) : null
  const stale = status === 'stale'
  const requiredCredits = loaded ? (jobs?.required_credits ?? null) : null
  const availableCredits = jobs?.available_credits ?? 0
  const insufficientCredits =
    requiredCredits !== null && requiredCredits > 0 && availableCredits < requiredCredits
  const isLoading = loading || !loaded
  const bodyLines = savedBody.split(/\r?\n/)
  const analyzed = status === 'ready' || status === 'no_suggestions' || stale
  /**
   * 결과가 저장되면 예약한 이용량을 **소비**한다 — 제안이 0건이어도 마찬가지다(명세 §3).
   * 실패·stale·만료만 반환이므로, 성공한 작업의 예약분을 그대로 사용액으로 읽는다.
   */
  const consumedCredits =
    currentJob?.status === 'succeeded' && currentJob.reserved_credits > 0
      ? currentJob.reserved_credits
      : null
  // 이용량 단가가 없으면 접수 자체가 503이다(계약 `IllustrationSuggestionJobCollectionResponse`).
  // 누를 수 없는 버튼 대신 이유를 적는다.
  const canRequest = requiredCredits !== null

  useEffect(() => {
    let disposed = false
    const controller = new AbortController()
    async function restore(): Promise<void> {
      try {
        const [nextResource, nextJobs] = await Promise.all([
          getIllustrationSuggestions(conversionId, controller.signal),
          listIllustrationSuggestionJobs(conversionId, controller.signal),
        ])
        if (disposed) return
        setError(null)
        setNotice(null)
        setPendingCreate(null)
        setJob(null)
        setResource(nextResource)
        setJobs(nextJobs)
        setPollingJobId(nextJobs.active_job?.job_id ?? null)
      } catch (caught) {
        if (disposed) return
        setResource(null)
        setJobs(null)
        setPollingJobId(null)
        setJob(null)
        setPendingCreate(null)
        setError(errorText(caught, 'load'))
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
    // 본문을 저장하면 서버가 **읽는 시점에** 결과를 stale 로 판정한다(계약 §
    // `IllustrationSuggestionsResponse`). 저장 뒤 다시 읽어야 화면이 그 사실을 말할 수
    // 있으므로 `contentRevision`도 의존성에 둔다(ExplanationsPanel과 같은 규칙).
  }, [conversionId, contentRevision])

  /**
   * 작업 폴링. `setInterval`이 아니라 **한 번 끝난 뒤 다시 예약**하는 사슬이다 — 한 번의
   * 왕복이 주기보다 오래 걸려도 요청이 겹치지 않고, 끝난 작업에는 다음 예약을 걸지 않는다.
   * 모든 호출에 `AbortSignal`을 실어 언마운트·문서 전환·본문 저장 때 중간 응답이 사라진
   * 화면에 상태를 쓰지 못하게 한다.
   */
  useEffect(() => {
    const id = pollingJobId
    if (id === null || !loaded) return
    const controller = new AbortController()
    let timer = 0

    async function poll(): Promise<boolean> {
      try {
        const next = await getIllustrationSuggestionJob(conversionId, id!, controller.signal)
        if (controller.signal.aborted) return false
        setJob(next)
        if (next.status === 'queued' || next.status === 'running') return true
        const [nextResource, nextJobs] = await Promise.all([
          getIllustrationSuggestions(conversionId, controller.signal),
          listIllustrationSuggestionJobs(conversionId, controller.signal),
        ])
        if (controller.signal.aborted) return false
        setResource(nextResource)
        setJobs(nextJobs)
        setPollingJobId(nextJobs.active_job?.job_id ?? null)
        return false
      } catch (caught) {
        if (controller.signal.aborted) return false
        // 일시적인 조회 실패로 폴링을 멈추지 않는다 — 작업은 서버에서 계속 돈다.
        setError(errorText(caught, 'load'))
        return true
      }
    }

    function schedule(): void {
      timer = window.setTimeout(() => {
        void poll().then((again) => {
          if (again && !controller.signal.aborted) schedule()
        })
      }, POLL_INTERVAL_MS)
    }

    schedule()
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [conversionId, contentRevision, loaded, pollingJobId])

  /**
   * 접수에 성공하면 눌렀던 버튼이 사라지고 진행 상태 문구가 그 자리에 온다. 초점을 그대로
   * 두면 body로 떨어져 키보드 사용자가 맥락을 잃으므로, 새로 생긴 상태 문구로 옮긴다.
   */
  useEffect(() => {
    if (!focusActiveStatusRef.current) return
    if (activeStatusRef.current === null) return
    focusActiveStatusRef.current = false
    activeStatusRef.current.focus()
  }, [activeJob?.job_id])

  useEffect(() => () => refreshControllerRef.current?.abort(), [])

  async function refresh(): Promise<void> {
    refreshControllerRef.current?.abort()
    const controller = new AbortController()
    refreshControllerRef.current = controller
    setBusy(true)
    setError(null)
    try {
      const [nextResource, nextJobs] = await Promise.all([
        getIllustrationSuggestions(conversionId, controller.signal),
        listIllustrationSuggestionJobs(conversionId, controller.signal),
      ])
      if (controller.signal.aborted) return
      setResource(nextResource)
      setJobs(nextJobs)
      setPollingJobId(nextJobs.active_job?.job_id ?? null)
      setJob(null)
      // 서버 상태를 다시 읽었으므로 「결과를 모르는 요청」도 더는 남겨 두지 않는다 —
      // 접수됐다면 위 목록에 나타나고, 아니라면 새 요청으로 다시 시작한다.
      setPendingCreate(null)
      setNotice('최신 상태를 불러왔습니다.')
    } catch (caught) {
      if (controller.signal.aborted) return
      setError(errorText(caught, 'load'))
    } finally {
      if (!controller.signal.aborted) setBusy(false)
    }
  }

  /**
   * 같은 클릭의 재시도는 같은 `request_id`를 다시 보낸다 — 서버는 그 키로 기존 작업을
   * 돌려주므로 중복 접수도 중복 예약도 생기지 않는다(계약 `createIllustrationSuggestionJob`).
   */
  async function submitCreate(body: CreateIllustrationSuggestionJobRequest): Promise<void> {
    if (requestRef.current) return
    requestRef.current = true
    setBusy(true)
    setError(null)
    setNotice(null)
    setPendingCreate(body)
    try {
      const created = await createIllustrationSuggestionJob(conversionId, body)
      setJob(created.job)
      setPollingJobId(created.job.job_id)
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
      // 접수 성공은 아래 「분석하고 있어요」 상태 문구가 말한다 — 같은 사실을 두 live
      // region 이 잇달아 읽지 않게 여기서 별도 안내를 남기지 않는다(UX 명세 §7).
      setPendingCreate(null)
      focusActiveStatusRef.current = true
    } catch (caught) {
      // 같은 키를 다시 보낼 값어치가 있는 것은 **결과를 모르는** 실패뿐이다(네트워크
      // 끊김·status 0). 402·409·429·503처럼 서버가 분명히 거절한 경우는 같은 키를 다시
      // 보내도 답이 같으므로 재전송 버튼을 내밀지 않고 기본 요청 버튼을 그대로 둔다.
      if (caught instanceof ApiError && caught.status !== 0) setPendingCreate(null)
      setError(errorText(caught, 'create'))
    } finally {
      requestRef.current = false
      setBusy(false)
    }
  }

  function startCreate(): void {
    if (busy || bodyDirty || bodyBusy || bodyConflict || activeJob !== null) return
    void submitCreate({
      request_id: crypto.randomUUID(),
      expected_content_revision: contentRevision,
    })
  }

  return (
    <section
      aria-labelledby={headingId}
      aria-busy={isLoading || undefined}
      className="mt-6 space-y-4 rounded-xl border border-border bg-card p-4 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={headingId} className="text-lg font-semibold">
          그림 제안
        </h2>
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="min-h-11"
          disabled={busy || isLoading}
          onClick={() => {
            void refresh()
          }}
        >
          상태 새로고침
        </Button>
      </div>

      <p className="text-sm text-muted-foreground">
        저장된 본문에서 그림으로 설명하면 이해하기 쉬운 부분을 찾아, 이유와 그릴 내용을 글로
        제안합니다. 이 단계에서는 그림을 만들지 않습니다.
      </p>
      <p className="text-sm text-muted-foreground">
        그림을 만들더라도 DOCX·HWPX·TXT 파일에는 새 그림이 포함되지 않습니다 — 웹 화면에서만 볼 수
        있습니다.
      </p>

      {requiredCredits === 0 && (
        <p className="rounded-md bg-secondary p-3 text-sm font-medium">
          무료 테스트 모드: 제안과 그림은 화면 흐름 확인용 예시입니다. 실제 AI 문맥 분석이나 이미지
          생성은 하지 않으며 크레딧을 차감하지 않습니다.
        </p>
      )}
      {isLoading && <p role="status">그림 제안 상태를 불러오는 중…</p>}

      {!isLoading && error !== null && (
        <p role="alert" className="rounded-md bg-danger/10 p-3 text-sm text-danger">
          {error}
        </p>
      )}
      {!isLoading && (insufficientCredits || error?.startsWith('이용량이 부족합니다')) && (
        <a
          className="inline-flex min-h-11 items-center font-semibold text-primary"
          href={USAGE_PATH}
        >
          이용량 화면으로 이동
        </a>
      )}
      {notice !== null && (
        <p role="status" className="text-sm">
          {notice}
        </p>
      )}

      {!isLoading && jobs !== null && (
        <div className="space-y-3 rounded-md border border-border p-4">
          {requiredCredits === null ? (
            <p className="text-sm">
              이용량이 설정되지 않았습니다. 지금은 그림 제안을 요청할 수 없습니다.
            </p>
          ) : requiredCredits === 0 ? (
            <p id={costId} className="font-medium">
              추가 차감 없음
            </p>
          ) : (
            <p id={costId} className="font-medium">
              필요 이용량 {formatCredits(requiredCredits)}크레딧 / 남은 이용량{' '}
              {formatCredits(availableCredits)}크레딧
            </p>
          )}

          {bodyDirty && (
            <p className="text-sm">
              저장하지 않은 본문 수정이 있습니다. 먼저 본문을 저장한 뒤 제안을 확인해 주세요.
            </p>
          )}
          {bodyConflict && <p className="text-sm">본문 충돌을 해결한 뒤 제안을 확인해 주세요.</p>}

          {activeJob !== null ? (
            <p ref={activeStatusRef} tabIndex={-1} role="status">
              그림 제안을 분석하고 있어요. 다른 화면으로 이동해도 계속됩니다.
            </p>
          ) : (
            canRequest && (
              <div className="flex flex-wrap items-center gap-2">
                {/* 결과를 모르는 요청이 남아 있는 동안에는 **같은 키의 재전송만** 내민다.
                    새 uuid를 보내는 기본 버튼을 나란히 두면 「같은 요청」이라는 안내와
                    달리 중복 접수·중복 예약을 만들 수 있다. */}
                {pendingCreate !== null ? (
                  <Button
                    type="button"
                    variant="outline"
                    className="min-h-11"
                    disabled={busy}
                    aria-describedby={costId}
                    onClick={() => {
                      void submitCreate(pendingCreate)
                    }}
                  >
                    같은 요청 다시 보내기
                  </Button>
                ) : (
                  <Button
                    type="button"
                    className="min-h-11"
                    disabled={busy || bodyDirty || bodyBusy || bodyConflict || insufficientCredits}
                    // 차감량은 버튼 이름이 아니라 설명으로 붙인다 — 이름에 넣으면 상태가
                    // 바뀔 때마다 버튼 이름이 달라져 같은 조작을 다른 것으로 읽게 된다.
                    aria-describedby={costId}
                    onClick={startCreate}
                  >
                    {analyzed ? '그림 제안 다시 확인' : '그림 제안 확인'}
                  </Button>
                )}
              </div>
            )
          )}
        </div>
      )}

      {!isLoading && currentJob?.status === 'failed' && (
        <p role="status" className="rounded-md bg-secondary p-3 text-sm">
          {FAILURE_TEXTS[currentJob.failure_code ?? 'outcome_unknown']} 예약한 이용량은
          반환됐습니다. 자동으로 다시 분석하지 않으며, 다시 확인하려면 새 요청이 필요합니다.
        </p>
      )}
      {!isLoading && currentJob?.status === 'superseded' && (
        <p role="status" className="rounded-md bg-secondary p-3 text-sm">
          분석 중 본문이 바뀌어 이 작업은 사용할 수 없습니다. 예약한 이용량은 반환됐습니다.
        </p>
      )}

      {!isLoading && stale && (
        <p role="status" className="rounded-md bg-secondary p-3 text-sm">
          이전 버전의 본문으로 만든 제안입니다. 내용은 그대로 볼 수 있지만 그림 만들기는 할 수
          없습니다. 현재 본문으로 다시 확인해 주세요.
        </p>
      )}
      {!isLoading && status === 'no_suggestions' && (
        <div role="status" className="space-y-1">
          <p>이 문서에서 추가 그림이 도움이 될 부분을 찾지 못했습니다.</p>
          {/* 제안이 0건이어도 분석은 끝났고 이용량은 소비된다(명세 §3). 실패로 오인하지
              않도록 「끝났다」와 「얼마를 썼다」를 함께 적는다. */}
          <p className="text-sm text-muted-foreground">
            분석은 정상적으로 끝났습니다.
            {consumedCredits !== null &&
              ` 이용량 ${formatCredits(consumedCredits)}크레딧을 사용했습니다.`}
          </p>
        </div>
      )}
      {!isLoading && analyzed && (resource?.dropped_count ?? 0) > 0 && (
        <p className="text-sm text-muted-foreground">
          원문에서 근거를 확인하지 못한 제안 {resource?.dropped_count}건은 제외했습니다.
        </p>
      )}

      {suggestions.length > 0 && (
        <>
          <p id={generationNoteId} className="text-sm text-muted-foreground">
            {requiredCredits === 0
              ? '제안을 확인한 뒤 테스트 그림을 만들어 미리보기·적용·제거를 체험할 수 있습니다.'
              : '그림 만들기는 다음 단계에서 제공합니다. 지금은 제안 내용만 확인할 수 있습니다.'}
          </p>
          <ul className="flex flex-col gap-3">
            {suggestions.map((item, index) => (
              <SuggestionCard
                key={`${conversionId}:${contentRevision}:${status}:${item.suggestion_id}`}
                suggestion={item}
                index={index}
                bodyLines={bodyLines}
                disabledNoteId={generationNoteId}
                testMode={requiredCredits === 0}
                testDisabled={
                  bodyDirty ||
                  bodyBusy ||
                  bodyConflict ||
                  stale ||
                  busy ||
                  activeJob !== null ||
                  resource?.based_on_content_revision !== contentRevision
                }
              />
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
