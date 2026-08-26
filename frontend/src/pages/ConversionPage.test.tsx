import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getConversion } from '../api/client'
import { POLL_INTERVAL_MS, POLL_TIMEOUT_MS } from '../conversion/useConversionPolling'
import { conversion, workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { ConversionPage } from './ConversionPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getConversion: vi.fn(),
}))

function renderPage() {
  return render(
    // 화면이 맥락으로 보여주는 작업 공간은 제공자에서 온다(§6.3).
    <WorkspaceContext.Provider value={workspaceContext()}>
      <MemoryRouter
        initialEntries={[{ pathname: '/conversions/c1', state: { sourceText: '원문' } }]}
      >
        <Routes>
          <Route path="/conversions/:conversionId" element={<ConversionPage />} />
        </Routes>
      </MemoryRouter>
    </WorkspaceContext.Provider>,
  )
}

/** 단계 표시 한 줄을 상태 문구까지 함께 읽는다. */
function stage(label: string): HTMLElement {
  const item = screen.getAllByRole('listitem').find((li) => li.textContent?.includes(label))
  if (item === undefined) {
    throw new Error(`단계 표시에 "${label}"이(가) 없습니다`)
  }
  return item
}

/** 폴링 주기만큼 시간을 흘려보내고, 그 사이 오간 응답 처리까지 끝낸다. */
async function tick(times = 1) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * times)
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.mocked(getConversion).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('변환 폴링', () => {
  it('대기 → 처리 중 → 완료 순으로 화면이 바뀐다', async () => {
    vi.mocked(getConversion)
      .mockResolvedValueOnce(conversion({ status: 'pending', easy_text: null }))
      .mockResolvedValueOnce(conversion({ status: 'processing', easy_text: null }))
      .mockResolvedValue(conversion({ status: 'done' }))
    renderPage()

    // 첫 조회는 주기를 기다리지 않고 바로 나간다.
    await act(async () => {})
    expect(screen.getByRole('status')).toHaveTextContent('변환을 기다리고 있습니다')

    await tick()
    expect(screen.getByRole('status')).toHaveTextContent('쉬운 글로 바꾸고 있습니다')

    await tick()
    expect(screen.getByRole('heading', { name: '쉬운 글 검수' })).toBeInTheDocument()
  })

  it('실패하면 원인 → 보존된 것 → 다음 행동 순서로 보여준다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'failed', easy_text: null, failure_code: 'LLMTruncatedError' }),
    )
    renderPage()

    await act(async () => {})

    // 원인은 이 화면의 유일한 alert다 — 조언까지 같은 문장에 붙이면 무엇이 일어났는지와
    // 무엇을 할지가 한 덩어리로 낭독된다(§6.3).
    expect(screen.getByRole('alert')).toHaveTextContent('문서가 길어 변환이 도중에 잘렸습니다')
    expect(screen.getByRole('alert')).not.toHaveTextContent('더 짧게 나눠 다시 올려 주세요')

    expect(screen.getByRole('heading', { name: '남아 있는 것' })).toBeInTheDocument()
    expect(screen.getByText(/이 변환은 변환 기록에 남아 있습니다/)).toBeInTheDocument()

    const next = screen.getByRole('heading', { name: '다음에 할 일' })
    expect(next).toBeInTheDocument()
    expect(screen.getByText(/더 짧게 나눠 다시 올려 주세요/)).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /문서 다시 올리기|다른 문서 올리기/ }),
    ).toBeInTheDocument()
  })

  it('모르는 사유 코드는 일반 안내로 받고 코드 자체는 화면에 없다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'failed', easy_text: null, failure_code: 'SomethingNew' }),
    )
    const view = renderPage()

    await act(async () => {})

    expect(screen.getByRole('alert')).toHaveTextContent('변환에 실패했습니다')
    // 사용자에게 의미 없는 내부 코드는 화면 어디에도 나오지 않는다(§6.3).
    expect(view.container.textContent).not.toContain('SomethingNew')
  })

  /**
   * 여기서 재는 것은 "완료 표시를 아끼는가"다.
   *
   * 서버는 `pending`·`processing`만 주고 마스킹·LLM 변환 중 어디인지는 알려주지 않는다.
   * 그런데 네 단계를 보여주는 화면은 진행률 막대처럼 앞 단계를 하나씩 채우고 싶어진다 —
   * 그 순간 화면은 서버가 말한 적 없는 사실("개인정보 확인이 끝났다")을 단정하게 된다.
   * 이 테스트가 깨진다면 그 되돌림이 일어난 것이므로, 통과시키려고 기대값을 고치지 말고
   * 단계 표시를 되돌려라(ConversionStages의 주석 참고).
   */
  it('아직 이르지 않은 단계를 완료로 표시하지 않는다', async () => {
    vi.mocked(getConversion)
      .mockResolvedValueOnce(conversion({ status: 'pending', easy_text: null }))
      .mockResolvedValue(conversion({ status: 'processing', easy_text: null }))
    renderPage()

    await act(async () => {})
    // pending: 접수만 끝났고 그 뒤는 아직 시작조차 하지 않았다.
    expect(stage('문서 접수')).toHaveTextContent('완료')
    expect(stage('개인정보 확인')).not.toHaveTextContent('완료')
    expect(stage('쉬운 글 변환')).not.toHaveTextContent('완료')
    expect(stage('검수 준비')).not.toHaveTextContent('완료')

    await tick()
    // processing: 일이 돌고 있다는 것만 알 뿐 어느 단계인지는 모른다 — 앞 단계를 완료로
    // 찍지 않고 두 단계를 함께 '진행 중'으로 둔다.
    expect(stage('개인정보 확인')).toHaveTextContent('진행 중')
    expect(stage('개인정보 확인')).not.toHaveTextContent('완료')
    expect(stage('쉬운 글 변환')).toHaveTextContent('진행 중')
    expect(stage('검수 준비')).not.toHaveTextContent('완료')
  })

  it('오래 걸리면 실패가 아니라 기록으로 갈 행동을 준다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'pending', easy_text: null }))
    renderPage()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_TIMEOUT_MS + POLL_INTERVAL_MS)
    })

    const notice = screen.getByRole('status')
    expect(notice).toHaveTextContent('예상보다 오래 걸리고 있습니다')
    // 실패가 아니므로 위험(빨강) 표시를 쓰지 않는다(§6.3).
    expect(screen.queryByRole('alert')).toBeNull()
    expect(notice.querySelector('.form-error, .field-error')).toBeNull()

    const link = screen.getByRole('link', { name: /변환 기록/ })
    expect(link).toHaveAttribute('href', '/history')
  })

  it('상태를 알리는 live region은 하나다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'processing', easy_text: null }),
    )
    renderPage()

    await act(async () => {})

    // 같은 사실을 두 번 낭독하지 않는다(§11).
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('완료되면 더 이상 서버에 묻지 않는다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
    renderPage()

    await act(async () => {})
    const afterDone = vi.mocked(getConversion).mock.calls.length
    await tick(3)

    expect(vi.mocked(getConversion).mock.calls.length).toBe(afterDone)
  })

  it('화면을 떠나면 폴링을 멈춘다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'pending', easy_text: null }))
    const view = renderPage()

    await act(async () => {})
    await tick()
    const beforeUnmount = vi.mocked(getConversion).mock.calls.length
    expect(beforeUnmount).toBeGreaterThan(1)

    view.unmount()
    await tick(3)

    // 인터벌을 정리하지 않으면 여기서 호출 수가 계속 늘어난다.
    expect(vi.mocked(getConversion).mock.calls.length).toBe(beforeUnmount)
  })
})
