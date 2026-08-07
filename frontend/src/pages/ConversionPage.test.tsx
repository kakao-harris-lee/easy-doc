import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getConversion } from '../api/client'
import { POLL_INTERVAL_MS } from '../conversion/useConversionPolling'
import { conversion } from '../test/factories'
import { ConversionPage } from './ConversionPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getConversion: vi.fn(),
}))

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/conversions/c1', state: { sourceText: '원문' } }]}>
      <Routes>
        <Route path="/conversions/:conversionId" element={<ConversionPage />} />
      </Routes>
    </MemoryRouter>,
  )
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

  it('실패하면 사유 코드에 맞는 안내를 보여준다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'failed', easy_text: null, failure_code: 'LLMTruncatedError' }),
    )
    renderPage()

    await act(async () => {})

    expect(screen.getByRole('alert')).toHaveTextContent('문서가 길어 변환이 도중에 잘렸습니다')
    expect(screen.getByRole('alert')).toHaveTextContent('더 짧게 나눠 다시 올려 주세요')
  })

  it('모르는 사유 코드는 일반 안내로 받는다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'failed', easy_text: null, failure_code: 'SomethingNew' }),
    )
    renderPage()

    await act(async () => {})

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('변환에 실패했습니다')
    // 사용자에게 의미 없는 내부 코드를 그대로 노출하지 않는다.
    expect(alert).not.toHaveTextContent('SomethingNew')
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
