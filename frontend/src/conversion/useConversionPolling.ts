/**
 * 변환 진행 상황 폴링.
 *
 * 서버가 변환 완료를 알려줄 방법이 없어(웹소켓·SSE 없음) 클라이언트가 주기적으로
 * 물어본다. 규칙은 두 가지다.
 *
 * 1. **끝나면 멈춘다.** done·failed는 더 바뀌지 않는 상태라 계속 물어봐야 서버만
 *    두드린다.
 * 2. **화면을 떠나면 멈춘다.** 인터벌과 진행 중인 요청을 정리하지 않으면, 사용자가
 *    다른 화면으로 간 뒤에도 요청이 계속 나가고 사라진 컴포넌트에 setState가 걸린다.
 *
 * 상한(POLL_TIMEOUT_MS)을 두는 이유: 워커가 죽으면 변환은 pending에 굳고, 안내가
 * 없으면 사용자는 끝나지 않는 진행 표시만 본다.
 */

import { useEffect, useState } from 'react'

import { ApiError, getConversion } from '../api/client'
import type { ConversionResponse } from '../api/types'

/** 조회 간격. 변환은 보통 수 초 걸리므로 2초면 체감이 충분하다. */
export const POLL_INTERVAL_MS = 2000

/** 이 시간이 지나도 끝나지 않으면 기다리기를 그만두고 안내한다. */
export const POLL_TIMEOUT_MS = 5 * 60 * 1000

export interface ConversionPolling {
  /** 마지막으로 읽은 변환. 첫 응답 전에는 null. */
  conversion: ConversionResponse | null
  /** 조회 자체가 실패했을 때의 문구 (변환 실패와 다르다). */
  error: string | null
  /** 상한까지 기다렸는데 끝나지 않았다. */
  timedOut: boolean
}

/**
 * 어느 변환을 읽은 결과인지 함께 담는다.
 *
 * 기록에서 다른 문서로 옮겨 가면 같은 화면이 그대로 남고 변환 식별자만 바뀐다. 그때
 * 이전 문서의 결과가 잠깐 보이면 안 되는데, 효과 안에서 상태를 되돌리면 렌더가 한 번
 * 더 도는 대신 그 사이 낡은 값이 화면에 나간다. 식별자를 상태에 함께 두면 "이 값이
 * 지금 보는 변환의 것인가"를 렌더 시점에 판단할 수 있다.
 */
interface PollState extends ConversionPolling {
  conversionId: string
}

/** 아직 아무것도 읽지 않은 상태. */
function emptyState(conversionId: string): PollState {
  return { conversionId, conversion: null, error: null, timedOut: false }
}

/** 더 물어볼 필요가 없는 상태인지. */
function isTerminal(conversion: ConversionResponse): boolean {
  return conversion.status === 'done' || conversion.status === 'failed'
}

/** 변환이 끝날 때까지(또는 화면을 떠날 때까지) 상태를 따라간다. */
export function useConversionPolling(conversionId: string): ConversionPolling {
  const [state, setState] = useState<PollState>(() => emptyState(conversionId))

  useEffect(() => {
    const controller = new AbortController()
    const deadline = Date.now() + POLL_TIMEOUT_MS
    let stopped = false
    let intervalId: ReturnType<typeof setInterval> | undefined

    /** 인터벌과 진행 중인 요청을 함께 정리한다 — 한쪽만 멈추면 요청이 새어 나간다. */
    function stop() {
      stopped = true
      if (intervalId !== undefined) {
        clearInterval(intervalId)
        intervalId = undefined
      }
      controller.abort()
    }

    async function tick() {
      try {
        const next = await getConversion(conversionId, controller.signal)
        if (stopped) {
          return
        }
        const timedOut = !isTerminal(next) && Date.now() >= deadline
        setState({ conversionId, conversion: next, error: null, timedOut })
        if (isTerminal(next) || timedOut) {
          stop()
        }
      } catch (caught) {
        if (stopped || (caught instanceof DOMException && caught.name === 'AbortError')) {
          return
        }
        // 조회가 한 번 실패해도 폴링은 이어간다 — 네트워크가 잠깐 끊긴 경우가 흔하고,
        // 그때마다 사용자가 새로고침해야 한다면 진행 표시의 의미가 없다.
        const message =
          caught instanceof ApiError
            ? caught.message
            : '변환 상태를 확인하지 못했습니다. 다시 확인하는 중입니다.'
        setState((previous) =>
          previous.conversionId === conversionId
            ? { ...previous, error: message }
            : { ...emptyState(conversionId), error: message },
        )
      }
    }

    // 첫 조회는 기다리지 않고 바로 한다 — 이미 끝난 변환을 열었을 때 2초를 빈 화면으로
    // 보낼 이유가 없다(기록에서 다시 들어오는 경로가 그렇다).
    void tick()
    intervalId = setInterval(() => void tick(), POLL_INTERVAL_MS)

    return stop
  }, [conversionId])

  // 아직 새 변환의 첫 응답이 오지 않았다면 이전 문서의 결과를 보여주지 않는다.
  return state.conversionId === conversionId ? state : emptyState(conversionId)
}
