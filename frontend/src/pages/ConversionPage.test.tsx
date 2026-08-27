import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, getConversion, getDocumentSource } from '../api/client'
import { POLL_INTERVAL_MS, POLL_TIMEOUT_MS } from '../conversion/useConversionPolling'
import { conversion, documentSource, workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import { ConversionPage } from './ConversionPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getConversion: vi.fn(),
  getDocumentSource: vi.fn(),
}))

/**
 * 화면을 연다.
 *
 * `sourceText`는 **붙여넣기 직후에만** 실려 오는 라우터 state다. 파일로 올렸거나
 * 기록에서 다시 들어왔거나 그냥 새로고침한 경로를 재려면 이 값을 주지 않는다 —
 * 그 세 경로가 옛 화면에서 왼쪽 패널이 비던 자리다.
 */
function renderPage(sourceText?: string) {
  return render(
    // 화면이 맥락으로 보여주는 작업 공간은 제공자에서 온다(§6.3).
    <WorkspaceContext.Provider value={workspaceContext()}>
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/conversions/c1',
            state: sourceText === undefined ? null : { sourceText },
          },
        ]}
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
  vi.mocked(getDocumentSource).mockReset()
  vi.mocked(getDocumentSource).mockResolvedValue(documentSource())
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

/**
 * 원문은 **폴링과 다른 궤도**로 온다.
 *
 * 종전에는 붙여넣기 직후 라우터 state 하나뿐이라 파일 업로드·기록 재진입·새로고침에서
 * 왼쪽 패널이 통째로 비었다. 비교할 대상이 없으면 검수가 성립하지 않는다 — 여기서 재는
 * 것은 «어느 경로로 들어와도 원문이 보이는가»와 «로딩·실패·원문을 갈라 말하는가»다.
 */
describe('추출된 원문', () => {
  it('라우터 state가 없는 경로(파일 업로드·새로고침)에서도 원문을 서버에서 가져와 보여준다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
    vi.mocked(getDocumentSource).mockResolvedValue(
      documentSource({ source_format: 'docx', source_text: '파일에서 뽑은 원문입니다.' }),
    )
    renderPage()

    // 원문 조회는 변환 조회의 첫 응답이 온 **뒤에** 시작한다 — 문서 식별자가 그 응답에만
    // 있기 때문이다. 그래서 두 번 흘려보낸다(이 파일은 가짜 타이머를 쓴다).
    await act(async () => {})
    await act(async () => {})

    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue('파일에서 뽑은 원문입니다.')
    // 문서 식별자는 변환 응답에서 온다 — 주소창에 있는 것은 변환 id뿐이다.
    expect(vi.mocked(getDocumentSource).mock.calls[0]?.[0]).toBe('d1')
  })

  it('변환이 끝나기 전에도 원문을 보여준다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'processing', easy_text: null }),
    )
    vi.mocked(getDocumentSource).mockResolvedValue(
      documentSource({ source_text: '아직 바꾸는 중인 원문.' }),
    )
    renderPage()

    await act(async () => {})
    await act(async () => {})

    // 결과가 아직 없을 뿐 원문은 이미 있다 — 기다리는 동안 무엇이 변환되는지 보여준다.
    expect(screen.getByRole('heading', { name: '쉬운 글로 바꾸는 중' })).toBeInTheDocument()
    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue('아직 바꾸는 중인 원문.')
  })

  it('404면 원문을 못 불러왔다고 말한다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
    vi.mocked(getDocumentSource).mockRejectedValue(new ApiError(404, '문서를 찾을 수 없습니다.'))
    renderPage()

    await act(async () => {})
    await act(async () => {})

    expect(screen.getByRole('heading', { name: '원문을 불러오지 못함' })).toBeInTheDocument()
    expect(screen.getByText('원문을 찾을 수 없습니다.')).toBeInTheDocument()
    expect(screen.queryByLabelText('원본 (읽기 전용)')).not.toBeInTheDocument()
  })

  it('불러오는 중에는 못 불러왔다고 말하지 않는다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
    // 끝나지 않는 조회 — «아직 답이 오지 않은» 순간을 그대로 붙잡아 둔다.
    vi.mocked(getDocumentSource).mockReturnValue(new Promise(() => undefined))
    renderPage()

    await act(async () => {})

    expect(screen.getByRole('heading', { name: '원문 불러오는 중' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '원문을 불러오지 못함' })).not.toBeInTheDocument()
    expect(screen.queryByText('원문을 찾을 수 없습니다.')).not.toBeInTheDocument()
  })

  it('붙여넣기 원문이 있어도 서버 응답으로 갈아탄다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'done' }))
    vi.mocked(getDocumentSource).mockResolvedValue(
      documentSource({ source_text: '서버가 뽑아 둔 원문.' }),
    )
    renderPage('넘겨받은 원문.')

    // 라우터 state 덕에 첫 화면부터 왼쪽이 비지 않는다.
    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue('넘겨받은 원문.')

    await act(async () => {})
    await act(async () => {})

    // 그러나 최종 진실은 서버 응답이다.
    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue('서버가 뽑아 둔 원문.')
  })

  /**
   * 실패 화면에서 원문이 **가장 쓸모 있다.** 이 화면이 시키는 다음 행동이 「문서 다시
   * 올리기」이므로, 추출된 원문이 눈앞에 있으면 그대로 복사해 재시도할 수 있다.
   */
  it('변환에 실패해도 원문을 보여 주고, 실패 사유가 여전히 유일한 alert다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'failed', easy_text: null, failure_code: 'LLMTruncatedError' }),
    )
    vi.mocked(getDocumentSource).mockResolvedValue(
      documentSource({ source_text: '실패한 변환의 원문.' }),
    )
    renderPage()

    await act(async () => {})
    await act(async () => {})

    const panel = screen.getByLabelText('원본 (읽기 전용)')
    expect(panel).toHaveValue('실패한 변환의 원문.')
    // 원문을 실제로 불러왔을 때만 「남아 있는 것」이 그 사실을 함께 말한다.
    expect(screen.getByText(/추출한 원문도 아래에 그대로 있습니다/)).toBeInTheDocument()

    // 실패 사유가 이 화면의 유일한 alert다 — 원문 패널이 두 번째 목소리로 끼어들지
    // 않는다(§11).
    const alerts = screen.getAllByRole('alert')
    expect(alerts).toHaveLength(1)
    expect(alerts[0]).toHaveTextContent('문서가 길어 변환이 도중에 잘렸습니다')

    // 1순위는 무엇이 잘못됐고 다음에 뭘 하느냐다 — 원문은 그 아래에 온다.
    const next = screen.getByRole('heading', { name: '다음에 할 일' })
    expect(next.compareDocumentPosition(panel)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('원문을 못 불러온 실패 화면은 원문이 남아 있다고 말하지 않는다', async () => {
    vi.mocked(getConversion).mockResolvedValue(
      conversion({ status: 'failed', easy_text: null, failure_code: 'LLMTruncatedError' }),
    )
    vi.mocked(getDocumentSource).mockRejectedValue(new ApiError(404, '문서를 찾을 수 없습니다.'))
    renderPage()

    await act(async () => {})
    await act(async () => {})

    expect(screen.getByRole('heading', { name: '원문을 불러오지 못함' })).toBeInTheDocument()
    expect(screen.queryByText(/추출한 원문도 아래에 그대로 있습니다/)).not.toBeInTheDocument()
    // 실패 사유는 여전히 하나뿐이다 — 원문을 못 불러온 것은 alert 로 알리지 않는다.
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('원문은 폴링하지 않는다 — 변환을 몇 번을 더 물어도 한 번만 가져온다', async () => {
    vi.mocked(getConversion).mockResolvedValue(conversion({ status: 'pending', easy_text: null }))
    renderPage()

    await act(async () => {})
    await tick(3)

    expect(vi.mocked(getConversion).mock.calls.length).toBeGreaterThan(1)
    expect(vi.mocked(getDocumentSource).mock.calls).toHaveLength(1)
  })
})
