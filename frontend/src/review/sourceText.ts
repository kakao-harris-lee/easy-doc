/**
 * 검수·진행 화면 왼쪽에 놓일 «원문»을 가져온다.
 *
 * 종전에는 원문이 **붙여넣기 직후 라우터 state 하나**뿐이었다. 그래서 파일로 올린 문서,
 * 기록에서 다시 연 문서, 그리고 **그냥 새로고침한 화면**에서 왼쪽 패널이 비었고 비교할
 * 대상이 없으니 검수가 성립하지 않았다. 이제 서버가 `GET /documents/{id}/source`로
 * 원문을 돌려주므로 화면은 그것을 **한 번** 가져온다.
 *
 * **폴링과 분리한다.** 변환 상태는 끝날 때까지 계속 물어봐야 하지만
 * (`useConversionPolling`), 원문은 문서 등록 시점에 확정돼 변하지 않는다 — 주기적으로
 * 다시 물어볼 이유가 없다. 그래서 이 훅은 문서 식별자가 정해지는 순간 한 번 부르고,
 * 실패했을 때 사용자가 직접 누르는 `retry`로만 다시 부른다.
 */

import { useEffect, useState } from 'react'

import { ApiError, NETWORK_ERROR_STATUS, getDocumentSource } from '../api/client'

/**
 * 원문을 못 가져온 이유. 사용자가 **다음에 할 일이 다르다**는 것이 나누는 기준이다
 * (§9 — 일어난 일, 보존된 데이터, 다시 할 수 있는 일).
 *
 * - `'not_found'` — 404. 보관 기간이 지나 파기됐거나 지워진 문서다. 다시 눌러도 같다.
 * - `'unreachable'` — 서버에 닿지 못했거나 그 밖의 오류. 다시 시도할 값이 있다.
 */
export type SourceFailure = 'not_found' | 'unreachable'

/**
 * 원문 패널이 지금 그려야 하는 것.
 *
 * 셋을 한 타입으로 묶어 두는 이유는 §9다 — **로딩·실패·원문은 서로 다른 상태**이고,
 * `string | null` 하나로는 「아직 안 왔다」와 「못 가져왔다」가 같은 값이 된다. 그러면
 * 불러오는 중에 「원문 없음」이 뜨는데, 그것은 거짓말이다.
 */
export type SourcePanelState =
  | { status: 'loading' }
  | { status: 'ready'; text: string }
  | { status: 'failed'; failure: SourceFailure }

/** 원문 패널에 넘길 값 한 묶음. 상태와 «다시 불러오기»는 늘 함께 간다. */
export interface DocumentSource {
  state: SourcePanelState
  /** 실패했을 때 다시 부른다. 성공한 뒤에 눌러도 해가 없지만 부를 곳이 없다. */
  retry: () => void
}

/**
 * 어느 문서를 읽은 결과인지 함께 담는다 — 기록에서 다른 문서로 옮겨 갈 때가 있다.
 *
 * 「아직 아무것도 읽지 않았다」는 이 타입이 아니라 `null`로 둔다. `documentId: null`을
 * 그 뜻으로 쓰면 **문서 식별자를 아직 모르는 첫 렌더**(변환 조회 전)에서 `null === null`이
 * 참이 되어, 손에 든 붙여넣기 원문이 있는데도 화면이 「불러오는 중」으로 가라앉는다.
 */
interface LoadState {
  documentId: string
  panel: SourcePanelState
}

/** 실패를 사용자의 다음 행동으로 갈라 준다. */
function failureOf(caught: unknown): SourceFailure {
  if (caught instanceof ApiError) {
    // 남의 문서·없는 문서·파기된 문서는 모두 404다(존재를 숨기려는 계약의 선택).
    if (caught.status === 404) {
      return 'not_found'
    }
    if (caught.status === NETWORK_ERROR_STATUS) {
      return 'unreachable'
    }
  }
  // 그 밖(5xx·형식이 어긋난 응답)은 다시 시도할 값이 있는 쪽으로 둔다.
  return 'unreachable'
}

/**
 * 문서의 원문을 한 번 가져온다.
 *
 * @param documentId 아직 모르면 `null`. 변환 조회의 첫 응답이 와야 알 수 있다.
 * @param initialText 붙여넣기 직후 라우터 state로 넘어온 원문. **첫 화면을 빠르게
 *   그리기 위한 값일 뿐 최종 진실이 아니다** — 서버 응답이 오면 그것으로 갈아탄다.
 *   서버에 닿지 못했을 때만(404가 아닐 때만) 이 값이 화면에 남는다: 손에 든 글이
 *   같은 문서의 원문인데 네트워크가 잠깐 끊겼다고 그것을 감출 이유가 없다.
 */
export function useDocumentSource(
  documentId: string | null,
  initialText: string | null,
): DocumentSource {
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState<LoadState | null>(null)

  useEffect(() => {
    if (documentId === null) {
      return
    }
    const controller = new AbortController()
    let stopped = false

    async function load(id: string): Promise<void> {
      try {
        const response = await getDocumentSource(id, controller.signal)
        if (stopped) {
          return
        }
        setLoaded({ documentId: id, panel: { status: 'ready', text: response.source_text } })
      } catch (caught) {
        if (stopped || (caught instanceof DOMException && caught.name === 'AbortError')) {
          return
        }
        setLoaded({ documentId: id, panel: { status: 'failed', failure: failureOf(caught) } })
      }
    }

    void load(documentId)
    return () => {
      stopped = true
      controller.abort()
    }
  }, [documentId, attempt])

  const retry = () => setAttempt((value) => value + 1)

  // 아직 이 문서의 응답이 오지 않았다면 앞 문서의 원문을 보여주지 않는다.
  const answer = loaded !== null && loaded.documentId === documentId ? loaded.panel : null

  if (answer === null || (answer.status === 'failed' && answer.failure === 'unreachable')) {
    if (initialText !== null) {
      return { state: { status: 'ready', text: initialText }, retry }
    }
  }
  return { state: answer ?? { status: 'loading' }, retry }
}
