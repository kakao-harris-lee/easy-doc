import { useEffect, useId, useMemo, useState } from 'react'

import { ApiError } from '../../api/client'
import { readAdminErrors } from '../../api/admin'
import type { AdminErrorsResponse } from '../../api/types'

const LOAD_ERROR_MESSAGE = '오류 집계를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 기간 선택 갈래. `custom`은 두 날짜를 직접 입력하는 경우다(`UsagePage`와 같은 관례). */
type PeriodMode = 'this-month' | 'custom'

type Period = { from?: string; to?: string } | null

/** 기간이 같으면 같은 참조를 유지한다 — effect가 렌더마다 다시 돌지 않게 한다. */
function periodParams(mode: PeriodMode, customFrom: string, customTo: string): Period {
  if (mode === 'this-month') {
    // 생략하면 서버가 이번 달 1일~오늘로 채운다(readWorkspaceUsage와 같은 기본값 규칙).
    return {}
  }
  return customFrom !== '' && customTo !== '' ? { from: customFrom, to: customTo } : null
}

function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

/** 「오류」 탭 — 기간별 코드 집계 + 최근 목록 (어드민 최소, 계약 2.25.0). 본문·프롬프트 없음. */
export function AdminErrorsTab() {
  const [mode, setMode] = useState<PeriodMode>('this-month')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [data, setData] = useState<AdminErrorsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fromId = useId()
  const toId = useId()

  const period = useMemo(
    () => periodParams(mode, customFrom, customTo),
    [mode, customFrom, customTo],
  )

  // 기간이 바뀌었는지 렌더 중에 잰다("렌더 중 상태 조정" 패턴, `UsagePage`와 같은
  // 이유) — effect 안에서 곧바로 setLoading(true)를 부르면 안 된다는 규칙을 지킨다.
  const periodKey = period === null ? '' : `${period.from ?? ''}~${period.to ?? ''}`
  const [renderedPeriodKey, setRenderedPeriodKey] = useState(periodKey)
  if (renderedPeriodKey !== periodKey) {
    setRenderedPeriodKey(periodKey)
    setData(null)
    setError(null)
    if (period !== null) {
      setLoading(true)
    }
  }

  useEffect(() => {
    if (period === null) {
      return
    }
    const controller = new AbortController()
    readAdminErrors(period, controller.signal)
      .then((response) => {
        setData(response)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          return
        }
        setError(caught instanceof ApiError ? caught.message : LOAD_ERROR_MESSAGE)
      })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [period])

  return (
    <div className="flex flex-col gap-4">
      <fieldset className="flex flex-col gap-3 rounded-[12px] border border-border bg-card p-4">
        <legend className="px-1 text-[15px] font-semibold text-foreground">조회 기간</legend>
        <div className="flex flex-wrap gap-4">
          <label className="flex min-h-11 cursor-pointer items-center gap-2 font-medium">
            <input
              className="accent-primary"
              type="radio"
              name="admin-errors-period"
              value="this-month"
              checked={mode === 'this-month'}
              onChange={() => setMode('this-month')}
            />
            이번 달
          </label>
          <label className="flex min-h-11 cursor-pointer items-center gap-2 font-medium">
            <input
              className="accent-primary"
              type="radio"
              name="admin-errors-period"
              value="custom"
              checked={mode === 'custom'}
              onChange={() => setMode('custom')}
            />
            직접 입력
          </label>
        </div>
        {mode === 'custom' && (
          <div className="flex flex-wrap gap-4">
            <div className="field">
              <label htmlFor={fromId}>시작일</label>
              <input
                id={fromId}
                type="date"
                value={customFrom}
                onChange={(event) => setCustomFrom(event.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor={toId}>종료일</label>
              <input
                id={toId}
                type="date"
                value={customTo}
                onChange={(event) => setCustomTo(event.target.value)}
              />
            </div>
          </div>
        )}
      </fieldset>

      {/* 직접 입력을 골랐지만 두 날짜가 아직 다 안 채워졌으면 빈 패널 대신 이유를
          말한다 — `period === null`이라 아래 어느 블록도 그리지 않는 상태를 사용자가
          "고장났다"로 오해하지 않게 한다. */}
      {mode === 'custom' && period === null && (
        <p className="text-sm text-muted-foreground" role="status">
          시작일과 종료일을 모두 입력하면 조회합니다.
        </p>
      )}

      {error !== null && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {loading && (
        <p className="py-6 text-center text-sm text-primary" role="status">
          불러오는 중입니다…
        </p>
      )}

      {!loading && error === null && data !== null && (
        <>
          <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
            <table className="usage-table">
              <caption>이 기간 실패 원인별 건수입니다.</caption>
              <thead>
                <tr>
                  <th scope="col">실패 사유</th>
                  <th scope="col">건수</th>
                </tr>
              </thead>
              <tbody>
                {data.counts.length === 0 ? (
                  <tr>
                    <td colSpan={2} className="text-muted-foreground">
                      이 기간에 실패한 변환이 없습니다.
                    </td>
                  </tr>
                ) : (
                  data.counts.map((count) => (
                    <tr key={count.failure_code}>
                      <th scope="row">{count.failure_code}</th>
                      <td className="tabular-nums">{formatCount(count.count)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
            {/* llm_calls(V18) provider 실패 — 위 표(conversions.failure_code)와 다른 축이다.
            개별 LLM 호출이 완성 자체를 못 받은 사유이며, 재시도로 결국 성공한 변환의
            실패 호출도 잡힌다(계약 2.26.0). */}
            <table className="usage-table">
              <caption>이 기간 LLM 호출 실패를 사유별 건수로 낸 표입니다.</caption>
              <thead>
                <tr>
                  <th scope="col">실패 사유</th>
                  <th scope="col">건수</th>
                </tr>
              </thead>
              <tbody>
                {data.provider_failures.length === 0 ? (
                  <tr>
                    <td colSpan={2} className="text-muted-foreground">
                      이 기간에 실패한 LLM 호출이 없습니다.
                    </td>
                  </tr>
                ) : (
                  data.provider_failures.map((count) => (
                    <tr key={count.failure_class}>
                      <th scope="row">{count.failure_class}</th>
                      <td className="tabular-nums">{formatCount(count.count)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
            {/* 본문·프롬프트는 계약이 애초에 실어 보내지 않는다(x-admin-only 노트). */}
            <table className="usage-table">
              <caption>최근 실패 50건입니다. 본문·프롬프트는 담지 않습니다.</caption>
              <thead>
                <tr>
                  <th scope="col">워크스페이스</th>
                  <th scope="col">실패 사유</th>
                  <th scope="col">일시</th>
                </tr>
              </thead>
              <tbody>
                {data.recent.length === 0 ? (
                  <tr>
                    <td colSpan={3} className="text-muted-foreground">
                      이 기간에 실패한 변환이 없습니다.
                    </td>
                  </tr>
                ) : (
                  data.recent.map((item) => (
                    <tr key={item.id}>
                      <th scope="row">{item.workspace_id}</th>
                      <td>{item.failure_code}</td>
                      <td>{new Date(item.created_at).toLocaleString('ko-KR')}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
