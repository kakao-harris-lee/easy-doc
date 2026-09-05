import {
  act,
  fireEvent,
  render as renderInDom,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, downloadExport, saveFeedback, saveReview } from '../api/client'
import { lookupTerm } from '../api/dictionary'
import type { ConversionResponse, FormatPreservation } from '../api/types'
import { setUnsavedChanges } from '../review/unsavedChanges'
import {
  conversion,
  segmentMap,
  segmentMapUnit,
  sourceFailed,
  sourceLoading,
  sourceReady,
} from '../test/factories'
import { ReviewEditor } from './ReviewEditor'

/**
 * 라우터 안에서 그린다.
 *
 * 에디터가 품고 있는 피드백 폼이 제출 뒤 변환 기록으로 가는 링크를 내놓으므로,
 * `<Link>`가 라우터 없이는 그려지지 않는다.
 */
function render(ui: ReactElement) {
  return renderInDom(<MemoryRouter>{ui}</MemoryRouter>)
}

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  saveReview: vi.fn(),
  downloadExport: vi.fn(),
  saveFeedback: vi.fn(),
}))

vi.mock('../api/dictionary', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/dictionary')>()),
  lookupTerm: vi.fn(),
}))

/**
 * 화면 폭을 고정한다.
 *
 * jsdom에는 matchMedia가 없어서 에디터는 기본값(2열)으로 그려진다. 탭 동작을 보려면
 * "좁은 화면"이라고 답하는 matchMedia를 꽂아야 한다.
 */
function stubViewport(splitView: boolean): void {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: splitView,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }))
}

beforeEach(() => {
  vi.mocked(saveReview).mockReset()
  vi.mocked(downloadExport).mockReset()
  vi.mocked(saveFeedback).mockReset()
  vi.mocked(lookupTerm).mockReset()
})

afterEach(() => {
  // 모듈 전역 상태라 테스트끼리 새지 않게 되돌린다(언마운트 정리와 같은 일).
  setUnsavedChanges(false)
  vi.unstubAllGlobals()
})

/**
 * 응답에서 `feedback_submitted_at` 키를 통째로 지운다.
 *
 * 계약은 「키는 늘 있고 값만 null일 수 있다」로 정하지만 그것은 **서버의 약속이지 이
 * 컴포넌트가 받는 값의 보장이 아니다** — 아직 이 필드를 싣지 않는 서버, 배포 시차로
 * 남아 있는 옛 번들에서 키 없이 들어온다. 기본 목(`conversion`)은 계약대로 키를 담아
 * 두고, 그 약속이 깨진 상황은 여기서만 만든다.
 */
function withoutFeedbackKey(response: ConversionResponse): ConversionResponse {
  const stripped: Partial<ConversionResponse> = { ...response }
  delete stripped.feedback_submitted_at
  return stripped as ConversionResponse
}

describe('검수 에디터', () => {
  it('저장한 수정본이 있으면 그것을 초기값으로 쓴다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: 'AI 초안입니다.', edited_text: '담당자가 고친 글.' })}
        source={sourceReady('원문입니다.')}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(
      '담당자가 고친 글.',
    )
    expect(screen.getByLabelText('원본 (읽기 전용)')).toHaveValue('원문입니다.')
  })

  it('저장한 수정본이 없으면 AI 초안을 초기값으로 쓴다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: 'AI 초안입니다.', edited_text: null })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue('AI 초안입니다.')
  })

  it('원문을 불러왔으면 왼쪽 패널에 읽기 전용으로 보여준다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('파일에서 뽑은 원문.')} />)

    const panel = screen.getByLabelText('원본 (읽기 전용)')
    expect(panel).toHaveValue('파일에서 뽑은 원문.')
    // 사용자가 고치는 것은 오른쪽 결과다 — 왼쪽은 끝까지 읽기 전용이다.
    expect(panel).toHaveAttribute('readonly')
  })

  /**
   * 원문을 못 가져온 경로에서 왼쪽에 빈 입력칸이 남아 있으면, 화면은 "못 가져왔다"가
   * 아니라 "아직 안 왔다" 또는 "여기에 원문을 적어야 한다"고 말하게 된다. 셋은 서로 다른
   * 상태이므로(DESIGN.md §9) 입력칸이 사라졌는지와 설명이 남았는지를 함께 고정한다.
   */
  it('원문을 못 불러왔으면 빈 입력칸 대신 실패를 설명한다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceFailed('not_found')} />)

    expect(screen.queryByLabelText('원본 (읽기 전용)')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '원문을 불러오지 못함' })).toBeInTheDocument()
    expect(screen.getByText('원문을 찾을 수 없습니다.')).toBeInTheDocument()
    // 「파일로 올려서」가 아니다 — 이제 원문이 없는 이유는 «불러오지 못했다» 하나뿐이다.
    expect(
      screen.queryByText('파일로 올린 문서는 이 화면에서 원문을 다시 표시하지 않습니다.'),
    ).not.toBeInTheDocument()
  })

  /**
   * 불러오는 중에 「원문 없음」을 보여주면 그것은 아직 참이 아닌 문장이다(§9).
   * 로딩과 실패가 같은 화면으로 뭉치는 순간 사용자는 기다리면 될 일을 포기한다.
   */
  it('원문을 불러오는 중에는 없다고 말하지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceLoading()} />)

    expect(screen.getByRole('heading', { name: '원문 불러오는 중' })).toBeInTheDocument()
    expect(screen.getByText('원문을 불러오고 있습니다…')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '원문을 불러오지 못함' })).not.toBeInTheDocument()
    expect(screen.queryByText('원문을 찾을 수 없습니다.')).not.toBeInTheDocument()
  })

  it('네트워크 실패에는 다시 불러올 행동을 주고, 404에는 주지 않는다', async () => {
    const user = userEvent.setup()
    const retry = vi.fn()
    const view = render(
      <ReviewEditor conversion={conversion()} source={sourceFailed('unreachable', retry)} />,
    )

    expect(screen.getByText('원문을 불러오지 못했습니다.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '원문 다시 불러오기' }))
    expect(retry).toHaveBeenCalledTimes(1)

    // 404는 다시 물어도 404다 — 눌러도 소용없는 행동을 제시하지 않는다(§15).
    view.rerender(
      <MemoryRouter>
        <ReviewEditor conversion={conversion()} source={sourceFailed('not_found')} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: '원문 다시 불러오기' })).not.toBeInTheDocument()
  })

  it('AI 초안임을 알리는 배너와 자리표시자 유실 경고를 보여준다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ missing_placeholders: ['[[카드번호1]]'] })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('note')).toHaveTextContent('AI가 만든 초안입니다')
    expect(screen.getByText(/\[\[카드번호1\]\]가 결과에서 빠졌습니다/)).toBeInTheDocument()
  })

  it('수정하면 저장 안 됨이 되고, 저장하면 저장한 시각을 남긴다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    expect(screen.getByRole('status')).toHaveTextContent('저장 전')

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')

    expect(screen.getByRole('status')).toHaveTextContent('저장 안 됨')

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    // 저장 여부는 토스트로 흘려보내지 않고 화면에 남는다(§9).
    expect(await screen.findByText(/^저장됨 · /)).toBeInTheDocument()
    expect(screen.queryByText('저장 안 됨')).not.toBeInTheDocument()
  })

  it('저장했다는 사실을 두 곳에서 낭독하지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    const success = await screen.findByText('검수 내용을 저장했습니다.')
    // 낭독되는 곳은 저장 상태 라벨 하나뿐이고, 버튼 옆 성공 안내는 눈으로만 본다.
    const announced = screen.getAllByRole('status')
    expect(announced).toHaveLength(1)
    expect(announced[0]).toHaveTextContent(/^저장됨 · /)
    expect(success).not.toHaveAttribute('role')
  })

  /*
    의견을 보낸 변환에서 상단이 「아직 저장한 검수 내용이 없습니다」만 말하면, 서버에
    잘 저장된 제출을 사용자가 실패로 읽는다. 두 사실은 서로를 지우지 않는다 — 수정본을
    저장하지 않은 것도 참이고, 의견을 보낸 것도 참이라 둘 다 적혀 있어야 한다.
  */
  it('의견을 보낸 변환은 저장 상태와 의견 보냄을 함께 적는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          reviewed_at: null,
          feedback_submitted_at: '2026-08-27T02:00:00Z',
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('status')).toHaveTextContent('저장 전')
    expect(screen.getByText(/고쳐서 저장한 내용은 없습니다/)).toBeInTheDocument()
    // 「아직 …이 없습니다」는 할 일이 남았다는 말로 읽혀 제출 실패로 오해된다.
    expect(screen.queryByText(/아직 저장한 검수 내용이 없습니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/^의견 보냄 · /)).toBeInTheDocument()
  })

  it('의견을 보낸 적이 없으면 의견 보냄을 적지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    expect(
      screen.getByText('아직 저장한 검수 내용이 없습니다. AI 초안 그대로입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/의견 보냄/)).not.toBeInTheDocument()
  })

  /*
    키가 없으면 「제출 안 함」으로 읽는다.

    `=== null`로 물으면 `undefined`가 그 갈래를 비켜 가, 아무 의견도 보내지 않은 변환에
    「의견 보냄 · Invalid Date」 배지가 뜬다. `new Date(undefined)`는 던지지 않고 Invalid
    Date를 만들기 때문에 화면이 조용히 거짓말을 한다 — 값이 없을 때 안전한 오답은
    「아직 안 보냈다」 쪽이다.
  */
  it('의견 제출 시각 키가 아예 없으면 제출 안 한 것으로 읽는다', () => {
    render(
      <ReviewEditor
        conversion={withoutFeedbackKey(conversion({ reviewed_at: null }))}
        source={sourceFailed()}
      />,
    )

    expect(
      screen.getByText('아직 저장한 검수 내용이 없습니다. AI 초안 그대로입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/의견 보냄/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Invalid Date/)).not.toBeInTheDocument()
  })

  /*
    토스트만 뜨고 상단이 그대로면 화면은 방금 일어난 일을 반영하지 않은 것이다.
    다시 조회하지 않고 서버가 응답에 실어 준 시각을 그대로 옮긴다.
  */
  it('의견을 보내면 상단 상태가 곧바로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveFeedback).mockResolvedValue({
      conversion_id: 'c1',
      publish_intent: 'with_edits',
      quality_score: 4,
      minutes_spent: 25,
      comment: null,
      submitted_at: '2026-08-27T02:00:00Z',
    })
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    expect(screen.queryByText(/의견 보냄/)).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('조금 고쳐서 쓰겠다'))
    await user.click(screen.getByLabelText('4점'))
    await user.type(screen.getByLabelText('이번 건 소요 시간(분)'), '25')
    await user.click(screen.getByRole('button', { name: '의견 보내기' }))

    expect(await screen.findByText(/^의견 보냄 · /)).toBeInTheDocument()
    // 화면을 대신 넘기지 않는다 — 검수 화면은 그대로 있고 돌아가는 길만 생긴다.
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '변환 기록으로 돌아가기' })).toBeInTheDocument()
    // 제출 성공은 폼의 안내가 이미 낭독한다. 상태 패널이 같은 사실을 한 번 더 읽지 않게
    // 이 배지는 `role="status"` 바깥에 있다(§11 중복 낭독 금지).
    const announced = screen.getAllByRole('status').map((node) => node.textContent ?? '')
    expect(announced.filter((text) => text.includes('의견 보냄'))).toHaveLength(0)
  })

  /*
    저장 버튼은 진행 중에 `disabled` 가 된다. 브라우저는 초점을 가진 요소가 잠기는 순간
    초점을 `<body>` 로 떨어뜨리므로, 되돌려 놓지 않으면 키보드 사용자는 저장 한 번에
    탭 경로를 통째로 잃고 문서 맨 앞에서 다시 밟아야 한다(§14).
  */
  it('저장이 끝나면 초점이 저장 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    const save = screen.getByRole('button', { name: '검수 내용 저장' })
    await user.click(save)

    await screen.findByText('검수 내용을 저장했습니다.')
    expect(screen.getByRole('button', { name: '검수 내용 저장' })).toHaveFocus()
  })

  it('내려받기가 끝나도 초점이 그 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['쉬운 글'], { type: 'text/plain' }),
      filename: '쉬운 글.txt',
    })
    // jsdom 에는 blob URL 도 anchor 내려받기도 없다 — 저장 경로만 통과시킨다.
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: () => 'blob:test',
      revokeObjectURL: () => undefined,
    })
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    await user.click(screen.getByRole('button', { name: 'TXT로 내려받기' }))

    await screen.findByText('TXT 파일을 내려받았습니다.')
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toHaveFocus()
  })

  it('수정한 글을 저장하고 결과를 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '고친 글.', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    await user.clear(editor)
    await user.type(editor, '고친 글.')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(vi.mocked(saveReview)).toHaveBeenCalledWith('c1', '고친 글.')
    expect(await screen.findByText('검수 내용을 저장했습니다.')).toBeInTheDocument()
  })

  it('저장에 실패하면 사유를 알리고 수정 내용을 그대로 둔다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockRejectedValue(new ApiError(409, '아직 완료되지 않은 변환입니다'))
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
    await user.type(editor, ' 덧붙임')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('아직 완료되지 않은 변환입니다')
    expect(editor).toHaveValue('초안. 덧붙임')
    // 실패했으므로 저장하지 않은 수정이라는 사실이 그대로 남아야 한다.
    expect(screen.getByRole('status')).toHaveTextContent('저장 안 됨')
  })

  it.each(['docx', 'hwpx', 'txt'] as const)(
    '%s 내려받기를 누르면 파일을 받아 저장한다',
    async (format) => {
      const user = userEvent.setup()
      const objectUrl = 'blob:test'
      const createObjectURL = vi.fn(() => objectUrl)
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
      vi.mocked(downloadExport).mockResolvedValue({
        blob: new Blob(['내용']),
        filename: `재난지원금 안내.${format}`,
      })
      render(
        <ReviewEditor conversion={conversion({ export_format: format })} source={sourceFailed()} />,
      )

      await user.click(screen.getByRole('button', { name: `${format.toUpperCase()}로 내려받기` }))

      expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', format)
      expect(click).toHaveBeenCalled()
      expect(revokeObjectURL).toHaveBeenCalledWith(objectUrl)
      expect(
        await screen.findByText(`${format.toUpperCase()} 파일을 내려받았습니다.`),
      ).toBeInTheDocument()

      click.mockRestore()
      vi.unstubAllGlobals()
    },
  )

  it('서버가 정한 형식 하나만 내려받기로 제시한다 — 교차 형식 버튼을 그리지 않는다', () => {
    render(
      <ReviewEditor conversion={conversion({ export_format: 'docx' })} source={sourceFailed()} />,
    )

    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    // 원본이 DOCX인데 txt·hwpx 버튼을 그리면 그 버튼은 서버에서 반드시 409로 실패한다.
    expect(screen.queryByRole('button', { name: 'TXT로 내려받기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'HWPX로 내려받기' })).not.toBeInTheDocument()
  })

  it('내려받을 수단이 없으면(export_format null, 선택지도 없음) 내려받기 행동을 제시하지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: null })}
        source={sourceFailed()}
      />,
    )

    // §6.5 — 상태를 지어내지 않는다. 저장은 여전히 할 수 있어야 한다.
    expect(screen.getByRole('button', { name: '검수 내용 저장' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /내려받기$/ })).not.toBeInTheDocument()
  })

  it('PDF 원본에 선택지가 있으면(export_format_choices) 형식마다 버튼을 하나씩 그린다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'pdf',
          export_format: null,
          export_format_choices: ['docx', 'hwpx'],
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'HWPX로 내려받기' })).toBeInTheDocument()
    // 선택지에 없는 형식의 버튼은 그리지 않는다.
    expect(screen.queryByRole('button', { name: 'TXT로 내려받기' })).not.toBeInTheDocument()
  })

  it.each(['docx', 'hwpx'] as const)(
    'PDF 선택지 중 %s를 누르면 그 형식으로 내려받는다',
    async (format) => {
      const user = userEvent.setup()
      const objectUrl = 'blob:test'
      const createObjectURL = vi.fn(() => objectUrl)
      const revokeObjectURL = vi.fn()
      vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
      vi.mocked(downloadExport).mockResolvedValue({
        blob: new Blob(['내용']),
        filename: `재난지원금 안내.${format}`,
      })
      render(
        <ReviewEditor
          conversion={conversion({
            source_format: 'pdf',
            export_format: null,
            export_format_choices: ['docx', 'hwpx'],
          })}
          source={sourceFailed()}
        />,
      )

      await user.click(screen.getByRole('button', { name: `${format.toUpperCase()}로 내려받기` }))

      expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', format)
      expect(click).toHaveBeenCalled()

      click.mockRestore()
      vi.unstubAllGlobals()
    },
  )

  it('마스킹 항목이 결과에 남아 있는지 표로 알려준다', async () => {
    const user = userEvent.setup()
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '등록번호는 [[주민등록번호1]]이에요.' })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByRole('row', { name: /주민등록번호1/ })).toHaveTextContent('있음')

    // 검수하다 자리표시자를 지우면 그 사실이 표에 바로 드러나야 한다.
    await user.clear(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'))

    expect(screen.getByRole('row', { name: /주민등록번호1/ })).toHaveTextContent('없음')
  })

  describe('좁은 화면', () => {
    it('원문이 있으면 원문·쉬운 글 탭으로 나누고 키보드로 옮길 수 있다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} source={sourceReady('원문입니다.')} />)

      expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['원문', '쉬운 글'])
      expect(screen.getByLabelText('원본 (읽기 전용)')).toBeVisible()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).not.toBeVisible()

      screen.getByRole('tab', { name: '원문' }).focus()
      await user.keyboard('{ArrowRight}')

      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
      expect(screen.getByLabelText('원본 (읽기 전용)')).not.toBeVisible()
    })

    it('원문을 아직 못 받았으면 탭을 만들지 않고 그 사실을 그대로 보여준다', () => {
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} source={sourceLoading()} />)

      // 「불러오는 중」을 탭 뒤에 숨기면 그 사실이 사용자에게 닿지 않는다(§9).
      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      expect(screen.getByRole('heading', { name: '원문 불러오는 중' })).toBeInTheDocument()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
    })

    it('원문을 못 불러왔으면 탭 없이 실패를 그대로 보여준다', () => {
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} source={sourceFailed('not_found')} />)

      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      expect(screen.getByText('원문을 찾을 수 없습니다.')).toBeInTheDocument()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
    })

    /**
     * 늦게 도착한 원문이 **편집 중인 화면을 가로채지 않는다.**
     *
     * 탭이 없는 동안에는 두 패널이 함께 보이므로 사용자는 결과 편집기에 바로 타이핑한다.
     * 그때 원문이 도착해 탭이 생기면서 활성 탭이 초기값 `원문`에 머무르면, 방금까지 고치던
     * 글과 초점이 눈앞에서 사라진다. 네트워크가 느릴수록 더 오래 타이핑하다 당한다.
     */
    it('원문이 늦게 도착해 탭이 생겨도 편집 중이던 결과 패널을 빼앗지 않는다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceLoading()} />)

      // 아직 탭이 없다 — 두 패널이 위아래로 모두 보인다.
      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
      await user.click(editor)
      await user.type(editor, ' 수정')
      expect(editor).toHaveFocus()

      // 원문 도착 — 여기서 탭이 생긴다.
      view.rerender(
        <MemoryRouter>
          <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
        </MemoryRouter>,
      )

      expect(screen.getAllByRole('tab')).toHaveLength(2)
      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(editor).toBeVisible()
      expect(editor).toHaveValue('초안. 수정')
      expect(editor).toHaveFocus()
    })

    it('아무것도 건드리지 않았다면 탭이 생길 때 원문이 먼저다', () => {
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceLoading()} />)

      view.rerender(
        <MemoryRouter>
          <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
        </MemoryRouter>,
      )

      // §11의 읽기 순서는 그대로다 — 원문 다음 결과.
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByLabelText('원본 (읽기 전용)')).toBeVisible()
    })

    /**
     * 전이할 때만 판정한다. 사용자가 원문 탭을 직접 고른 뒤 창 크기가 오갔다고 결과로
     * 튕기면 그것도 같은 종류의 가로채기다.
     */
    it('탭이 사라졌다 다시 생겨도 사용자가 고른 탭을 덮어쓰지 않는다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />)

      // 고쳐 두고(=dirty) 원문 탭을 직접 고른다.
      await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
      await user.click(screen.getByRole('tab', { name: '원문' }))
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')

      // 넓어졌다가(탭 사라짐) 다시 좁아진다(탭 생김).
      const rerender = (splitView: boolean) => {
        stubViewport(splitView)
        view.rerender(
          <MemoryRouter>
            <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
          </MemoryRouter>,
        )
      }
      rerender(true)
      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      rerender(false)

      // 초점은 어느 입력칸에도 없지만 고친 내용은 있다 — 그래도 사용자가 마지막으로
      // 고른 원문 탭이 이긴다.
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')
    })

    /**
     * **초점은 과거의 탭 선택보다 강한 신호다.**
     *
     * 탭을 골라 둔 사람이 화면을 넓혀(탭 소멸) 결과를 고치다가 다시 좁히면, 그 선택을
     * 존중한다는 이유로 편집 중이던 결과 패널이 숨는다 — 처음에 고친 것과 같은 버그가
     * 한 단계 뒤에 남아 있던 자리다.
     */
    it('탭을 골라 둔 뒤라도 넓혔다 좁힐 때 초점이 있는 패널을 빼앗지 않는다', async () => {
      const user = userEvent.setup()
      stubViewport(false)
      const target = conversion({ easy_text: '초안.' })
      const view = render(<ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />)
      const rerender = (splitView: boolean) => {
        stubViewport(splitView)
        view.rerender(
          <MemoryRouter>
            <ReviewEditor conversion={target} source={sourceReady('원문입니다.')} />
          </MemoryRouter>,
        )
      }

      // ① 원문 탭을 직접 고른다.
      await user.click(screen.getByRole('tab', { name: '원문' }))
      expect(screen.getByRole('tab', { name: '원문' })).toHaveAttribute('aria-selected', 'true')

      // ② 넓힌다 — 탭이 사라지고 두 패널이 모두 보인다.
      rerender(true)
      expect(screen.queryAllByRole('tab')).toHaveLength(0)

      // ③ 결과 편집기에 초점을 두고 타이핑한다.
      const editor = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
      await user.click(editor)
      await user.type(editor, ' 수정')
      expect(editor).toHaveFocus()

      // ④ 다시 좁힌다 — 여기서 결과 패널이 숨으면 안 된다.
      rerender(false)

      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(editor).toBeVisible()
      expect(editor).toHaveValue('초안. 수정')
      expect(editor).toHaveFocus()
    })
  })
})

/**
 * jsdom 에는 blob URL 도 anchor 내려받기도 없다 — 저장 경로만 통과시킨다.
 * 되돌리기는 전역 afterEach 의 `vi.unstubAllGlobals()` 가 맡는다.
 */
function stubBlobSaving(): void {
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: () => 'blob:test',
    revokeObjectURL: () => undefined,
  })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
}

describe('원본 서식 유지 패널', () => {
  it.each([
    ['docx', 'DOCX'],
    ['hwpx', 'HWPX'],
  ] as const)('%s 원본이면 패널을 그리고 형식을 밝힌다', (sourceFormat, label) => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: sourceFormat,
          export_format: sourceFormat,
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText(label)).toBeInTheDocument()
    expect(within(panel).getByText('유지 가능')).toBeInTheDocument()
  })

  /** §6.5 표 — 붙여넣기는 「적용 대상 아님」이라 패널 자체가 없다. */
  it('붙여넣기(TXT)에는 패널을 그리지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} source={sourceReady('원문입니다.')} />)

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
  })

  /**
   * 상태를 색으로만 가르지 않는다(§8.1) — 상태마다 **문구가 다르다.** 아이콘도 다른 것을
   * 쓰지만, 낭독기와 흑백 화면에서 남는 단서는 이 라벨이다.
   */
  it.each<[FormatPreservation, string]>([
    [{ status: 'available', details: [] }, '유지 가능'],
    [{ status: 'partial', details: ['표 1개는 단순 표로 바뀝니다.'] }, '일부 유지'],
    [{ status: 'failed', details: ['원본 파일을 열 수 없습니다.'] }, '서식 유지 실패'],
  ])('상태 %o 를 색이 아닌 문구로 구분한다', (preservation, label) => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: preservation,
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText(label)).toBeInTheDocument()
  })

  /**
   * 유지할 원본이 없다는 판정(`not_applicable`)에는 아무 말도 하지 않는다. 없는 원본을
   * 두고 상태를 말하는 것은 사용자가 할 일이 없는 정보를 화면에 세우는 일이다.
   */
  it('유지할 원본이 없으면(not_applicable) 상태 표시를 그리지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'not_applicable', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
  })

  /**
   * `일부 유지`는 **정상 결과다.** 이 제품이 약속하는 유지 수준은 「심하게 틀어지지 않는
   * 정도」이고 문단 수가 어긋나는 것은 예상된 결과다 — 다시 시도해도 같은 답이 온다.
   * 조치할 수 없는 정보를 경고색·경고 아이콘으로 그리면 소음이고, 정작 조치가 필요한
   * `failed`의 강조가 묻힌다.
   */
  it('일부 유지를 경고처럼 그리지 않는다 — 강조는 실패에만 있다', () => {
    const { unmount } = render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'partial', details: ['문단 3개는 본문 끝에 덧붙습니다.'] },
        })}
        source={sourceFailed()}
      />,
    )

    const partial = within(screen.getByRole('region', { name: '원본 서식 유지' })).getByText(
      '일부 유지',
    )
    expect(partial.className).not.toMatch(/warning|danger/)
    // 경고가 아니므로 낭독기를 끊는 알림도 아니고, 다시 시도하라는 안내도 붙지 않는다.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/다시 눌러 시도할 수 있고/)).not.toBeInTheDocument()
    unmount()

    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'failed', details: ['원본 파일을 열 수 없습니다.'] },
        })}
        source={sourceFailed()}
      />,
    )

    const failed = within(screen.getByRole('region', { name: '원본 서식 유지' })).getByText(
      '서식 유지 실패',
    )
    expect(failed.className).toMatch(/danger/)
  })

  /** `partial`의 내용은 서버 문구가 정본이다 — 화면이 개수를 다시 세거나 문장을 짓지 않는다. */
  it('일부 유지에서는 서버가 준 항목을 그대로 나열한다', () => {
    const details = [
      '머리말·꼬리말 2곳은 원본 문구를 그대로 둡니다.',
      '문단 3개는 원본에 자리가 없어 본문 끝에 덧붙습니다.',
    ]
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: { status: 'partial', details },
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    for (const detail of details) {
      expect(within(panel).getByText(detail)).toBeInTheDocument()
    }
  })

  /**
   * §6.5 — 서식을 유지할 수 없을 때 텍스트 전용 파일로 조용히 대체하지 않는다. 사유와
   * 다시 시도 행동을 보이고, 내려받기 버튼은 그 「다시 시도」로 그 자리에 남는다.
   */
  it('서식 유지 실패는 사유와 다시 할 수 있는 일을 보이되 다른 형식으로 우회하지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: {
            status: 'failed',
            details: ['원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다.'],
          },
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(
      within(panel).getByText('원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다.'),
    ).toBeInTheDocument()
    expect(within(panel).getByText(/다시 눌러 시도할 수 있고/)).toBeInTheDocument()
    // 같은 형식으로 다시 시도하는 길만 남는다 — txt로 대신 받는 버튼을 만들지 않는다.
    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /TXT/ })).not.toBeInTheDocument()
  })

  /**
   * 서버가 아직 판정하지 않은 상태(null)를 「유지 가능」으로 낙관하지 않는다(§6.5).
   * 계약에 `checking`이 없으므로 진행 표시(스피너)도 만들지 않는다.
   */
  it('판정이 아직 없으면 확인되지 않음으로 두고 진행 표시를 만들지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          format_preservation: null,
        })}
        source={sourceFailed()}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('확인되지 않음')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).not.toBeInTheDocument()
    expect(within(panel).queryByRole('status')).not.toBeInTheDocument()
  })

  /**
   * PDF는 §6.5 표에서 서식 유지 패널의 대상이 아니다. 대신 내려받기 버튼이 **없는 이유**를
   * 말한다 — 아무 설명 없이 자리를 비우면 화면이 고장 난 것처럼 보인다.
   */
  it('PDF 원본에 선택지가 없으면 상태 표시 대신 내려받기가 없는 이유를 말한다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: null })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByText(/PDF는 출력용 형식이라/)).toBeInTheDocument()
    expect(screen.getByText(/업로드와 변환, 검수와 저장은 그대로 됩니다/)).toBeInTheDocument()
    // 곧 될 것처럼 적지 않는다 — 하지 않기로 정해진 범위다.
    expect(screen.queryByText(/준비 중/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /내려받기$/ })).not.toBeInTheDocument()
  })

  /**
   * 2.6.0 — PDF에 선택지가 있으면(`export_format_choices`) 「내려받기가 없다」는
   * 옛 문구를 더는 쓰지 않는다. 버튼이 실제로 있으므로 그 자리에는 무엇이 나오는지만
   * 말한다: 원본 레이아웃은 반영되지 않는 새 문서라는 사실.
   */
  it('PDF 원본에 선택지가 있으면 내려받기가 없다는 문구 대신 새 문서라는 안내를 보여준다', () => {
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'pdf',
          export_format: null,
          export_format_choices: ['docx', 'hwpx'],
        })}
        source={sourceFailed()}
      />,
    )

    expect(screen.queryByRole('region', { name: '원본 서식 유지' })).not.toBeInTheDocument()
    expect(screen.getByText(/원본 레이아웃을 그대로 유지할 수 없습니다/)).toBeInTheDocument()
    expect(screen.getByText(/새 문서를 만들어 드립니다/)).toBeInTheDocument()
    // 더는 참이 아닌 옛 문구를 남기지 않는다 — 버튼이 실제로 있다.
    expect(screen.queryByText(/이 문서에는\s*내려받기가 없습니다/)).not.toBeInTheDocument()
    expect(screen.queryByText(/준비 중/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'HWPX로 내려받기' })).toBeInTheDocument()
  })

  /**
   * 판정은 **검수본의 문단 수**에서 나온다 — 원본 구조 단위와 짝이 맞으면 `available`,
   * 어긋나면 `partial`이다. 그래서 담당자가 검수하며 문단을 나누면 **서버의 답이 바뀐다.**
   *
   * 저장 응답은 `GET`과 같은 스키마라 그 새 판정을 싣고 온다. 화면이 조회 때 받은 값을
   * 붙들고 있으면 패널이 「유지 가능」이라고 말한 뒤 그와 다른 파일이 내려간다 — §6.5의
   * 「상태는 낙관적으로 추측하지 않는다. 서버가 제공한 결과만 표시한다」를 어기는 자리다.
   */
  it('문단을 나눠 저장하면 서버가 새로 잰 판정으로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        source_format: 'docx',
        export_format: 'docx',
        edited_text: '첫 문단\n둘째 문단',
        reviewed_at: '2026-08-07T02:00:00Z',
        format_preservation: {
          status: 'partial',
          details: ['문단 1개는 원본에 자리가 없어 본문 끝에 덧붙습니다.'],
        },
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          easy_text: '첫 문단',
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    expect(
      within(screen.getByRole('region', { name: '원본 서식 유지' })).getByText('유지 가능'),
    ).toBeInTheDocument()

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), '{enter}둘째 문단')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    await screen.findByText('검수 내용을 저장했습니다.')
    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('일부 유지')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).toBeNull()
    expect(
      within(panel).getByText('문단 1개는 원본에 자리가 없어 본문 끝에 덧붙습니다.'),
    ).toBeInTheDocument()
  })

  /**
   * 계약은 `format_preservation` 키가 **늘 있고 값이 `null`일 수 있다**고 정한다. 저장
   * 응답의 `null`도 서버의 답(「아직 판정하지 않았다」)이므로 지난 조회의 판정으로 메우지
   * 않는다 — `??`로 옛 값을 붙들면 화면이 서버가 하지 않은 말을 하게 된다.
   */
  it('저장 응답의 판정이 null이면 옛 판정을 붙들지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        source_format: 'docx',
        export_format: 'docx',
        edited_text: '초안. 수정',
        reviewed_at: '2026-08-07T02:00:00Z',
        format_preservation: null,
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          easy_text: '초안.',
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    await screen.findByText('검수 내용을 저장했습니다.')
    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('확인되지 않음')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).toBeNull()
  })
})

describe('저장하고 내려받기', () => {
  /**
   * §6.5 「한 번의 명확한 행동」 갈래도 저장을 지난다 — 그러므로 판정도 같이 갱신된다.
   * 이 경로에서 갱신이 빠지면 사용자가 방금 받은 파일과 화면의 약속이 어긋난 채로 남는다.
   */
  it('저장하고 내려받기에서도 판정이 갱신된다', async () => {
    const user = userEvent.setup()
    stubBlobSaving()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        source_format: 'docx',
        export_format: 'docx',
        edited_text: '첫 문단\n둘째 문단',
        reviewed_at: '2026-08-07T02:00:00Z',
        format_preservation: {
          status: 'partial',
          details: ['문단 1개는 원본에 자리가 없어 본문 끝에 덧붙습니다.'],
        },
      }),
    )
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '안내문-쉬운글.docx',
    })
    render(
      <ReviewEditor
        conversion={conversion({
          source_format: 'docx',
          export_format: 'docx',
          easy_text: '첫 문단',
          format_preservation: { status: 'available', details: [] },
        })}
        source={sourceFailed()}
      />,
    )

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), '{enter}둘째 문단')
    await user.click(screen.getByRole('button', { name: '저장하고 DOCX로 내려받기' }))

    await screen.findByText('검수 내용을 저장하고 DOCX 파일을 내려받았습니다.')
    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText('일부 유지')).toBeInTheDocument()
    expect(within(panel).queryByText('유지 가능')).toBeNull()
  })

  /** §6.5 — 저장하지 않은 수정이 있으면 두 걸음을 한 번의 행동으로 제공한다. */
  it('저장하지 않은 수정이 있으면 버튼이 저장까지 한다고 말하고 실제로 저장한 뒤 내려받는다', async () => {
    const user = userEvent.setup()
    stubBlobSaving()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '안내문-쉬운글.txt',
    })
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')

    await user.click(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' }))

    expect(vi.mocked(saveReview)).toHaveBeenCalledWith('c1', '초안. 수정')
    expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', 'txt')
    expect(
      await screen.findByText('검수 내용을 저장하고 TXT 파일을 내려받았습니다.'),
    ).toBeInTheDocument()
    // 저장까지 끝났으므로 버튼은 다시 한 걸음짜리로 돌아간다.
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toBeInTheDocument()
  })

  it('저장할 것이 없으면 버튼은 내려받기만 말하고 저장을 부르지 않는다', async () => {
    const user = userEvent.setup()
    stubBlobSaving()
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '안내문-쉬운글.txt',
    })
    render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

    await user.click(screen.getByRole('button', { name: 'TXT로 내려받기' }))

    expect(vi.mocked(saveReview)).not.toHaveBeenCalled()
    expect(await screen.findByText('TXT 파일을 내려받았습니다.')).toBeInTheDocument()
  })

  /**
   * §9 — 어느 걸음에서 멈췄는지, 무엇이 남아 있는지, 다음에 무엇을 할지가 갈래마다 다르다.
   * 저장부터 실패했으면 **파일을 만들지 않았다**는 사실까지 말해야 한다.
   */
  it('저장에 실패하면 내려받지 않고 그 사실을 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockRejectedValue(new ApiError(409, '아직 완료되지 않은 변환입니다'))
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('내려받지 않았습니다')
    expect(alert).toHaveTextContent('아직 완료되지 않은 변환입니다')
    expect(alert).toHaveTextContent('고친 내용은 화면에 그대로 있습니다')
    expect(vi.mocked(downloadExport)).not.toHaveBeenCalled()
    // 저장이 안 됐으므로 화면은 여전히 「저장 안 됨」이고 버튼도 두 걸음짜리 그대로다.
    expect(screen.getByRole('status')).toHaveTextContent('저장 안 됨')
    expect(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' })).toBeInTheDocument()
  })

  it('저장은 됐는데 내려받기가 실패하면 저장된 사실과 다음 행동을 구분해 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    vi.mocked(downloadExport).mockRejectedValue(new ApiError(409, '자리표시자가 빠졌습니다'))
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} source={sourceFailed()} />)

    await user.type(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'), ' 수정')
    await user.click(screen.getByRole('button', { name: '저장하고 TXT로 내려받기' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('검수 내용은 저장했습니다')
    expect(alert).toHaveTextContent('자리표시자가 빠졌습니다')
    expect(alert).toHaveTextContent('내려받기를 다시 눌러 주세요')
    // 저장은 실제로 끝났다 — 그 사실이 상태 라벨에도 남는다(§9).
    expect(await screen.findByText(/^저장됨 · /)).toBeInTheDocument()
  })
})

/**
 * 문단 단위 대응(`segment_map`, 계약 2.12.0) — 계획 §6 S3.
 *
 * 기본 목(`conversion()`)의 `segment_map`은 `null`이라 위 모든 테스트는 옛 단일
 * 에디터 경로를 그대로 탄다. 여기서는 `segment_map`을 명시로 채운 변환만 다룬다.
 */
describe('문단 단위 대응(segment_map)', () => {
  it('segment_map이 null이면 옛 단일 에디터를 그대로 쓴다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ segment_map: null, easy_text: '첫 문단\n둘째 문단' })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(
      '첫 문단\n둘째 문단',
    )
    expect(screen.queryByLabelText(/쉬운 글 단위 1/)).not.toBeInTheDocument()
  })

  it('HIGH 대응은 「대응 확인」, LOW는 「추정」 배지로 구분하고 각 단위를 textarea로 그린다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [], confidence: 'low' }),
      ],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 문단 하나\n원본 문단 둘')}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫 문장')
    expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('둘째 문장')
    expect(screen.getByLabelText('원본 1번째 문단')).toHaveValue('원본 문단 하나')
    expect(screen.getByLabelText('원본 2번째 문단')).toHaveValue('원본 문단 둘')
    expect(screen.getByText('대응 확인')).toBeInTheDocument()
    expect(screen.getByText('추정')).toBeInTheDocument()
  })

  it('쉬운 글 단위를 hover·focus하면 대응하는 원본 단위가 밝혀지고, 반대 방향도 같다', async () => {
    const user = userEvent.setup()
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫 문장\n둘째 문장', segment_map: map })}
        source={sourceReady('원본 하나\n원본 둘')}
      />,
    )

    const easyUnit1 = screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')
    const sourceUnit1 = screen.getByLabelText('원본 1번째 문단')
    const sourceUnit2 = screen.getByLabelText('원본 2번째 문단')

    expect(sourceUnit1.className).not.toMatch(/border-primary/)
    await user.click(easyUnit1)
    expect(sourceUnit1.className).toMatch(/border-primary/)
    expect(sourceUnit2.className).not.toMatch(/border-primary/)

    await user.tab() // easyUnit1에서 초점을 떼어 하이라이트를 지운다.
    expect(sourceUnit1.className).not.toMatch(/border-primary/)

    // 반대 방향 — 원본 단위를 hover·focus하면 대응하는 쉬운 글 단위가 밝혀진다.
    await user.click(sourceUnit2)
    const easyUnit2 = screen.getByLabelText('쉬운 글 단위 2, 원본 2번째 문단에 대응')
    expect(easyUnit2.className).toMatch(/border-primary/)
  })

  it('LOW 대응은 hover해도 원본 단위를 밝히지 않는다', async () => {
    const user = userEvent.setup()
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'low' })],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '문장 하나', segment_map: map })}
        source={sourceReady('원본 하나')}
      />,
    )

    const easyUnit = screen.getByLabelText('쉬운 글 단위 1, 대응 확인 불가')
    const sourceUnit = screen.getByLabelText('원본 1번째 문단')
    await user.click(easyUnit)
    expect(sourceUnit.className).not.toMatch(/border-primary/)
  })

  it('단위 안에서 Enter를 누르면 그 자리를 나누고 뒤 단위 번호가 밀린다', async () => {
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit = screen.getByLabelText(/쉬운 글 단위 1/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1)
    fireEvent.keyDown(unit, { key: 'Enter' })

    // 나뉜 두 단위 모두 원래 단위의 대응(high, 원본 1번째 문단)을 그대로 물려받는다 —
    // 국소 재계산은 서버가 다시 잴 때까지의 최선 추정이다(계획 §6 S3).
    expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫')
    expect(screen.getByLabelText('쉬운 글 단위 2, 원본 1번째 문단에 대응')).toHaveValue('줄')
  })

  it('분할 뒤 저장하면 무손실로 이은 문자열 하나가 그대로 서버로 간다', async () => {
    const user = userEvent.setup()
    const map = segmentMap({
      source_unit_count: 1,
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    vi.mocked(saveReview).mockResolvedValue(
      conversion({
        edited_text: '첫\n줄',
        reviewed_at: '2026-08-07T02:00:00Z',
        segment_map: map,
      }),
    )
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit = screen.getByLabelText(/쉬운 글 단위 1/) as HTMLTextAreaElement
    unit.focus()
    unit.setSelectionRange(1, 1)
    fireEvent.keyDown(unit, { key: 'Enter' })

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    // split('\n') ↔ join('\n') 왕복 — 화면이 단위 목록으로 바뀌어도 저장 계약은
    // `updateConversion` 하나뿐이고 값은 그대로 이은 문자열이다(계획 §2).
    expect(vi.mocked(saveReview)).toHaveBeenCalledWith('c1', '첫\n줄')
  })

  it('맨 앞에서 Backspace를 누르면 앞 단위와 합치고, 둘 다 high일 때만 합친 단위도 high다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'low' }),
      ],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄\n둘째줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit2 = screen.getByLabelText(/쉬운 글 단위 2/) as HTMLTextAreaElement
    unit2.focus()
    unit2.setSelectionRange(0, 0)
    fireEvent.keyDown(unit2, { key: 'Backspace' })

    // 둘 중 하나가 low였으므로 합친 단위도 low다 — 「대응 확인 불가」로 낮춰 안전하게 그린다.
    expect(screen.getByLabelText('쉬운 글 단위 1, 대응 확인 불가')).toHaveValue('첫줄둘째줄')
    expect(screen.queryByLabelText(/쉬운 글 단위 2/)).not.toBeInTheDocument()
  })

  it('화살표 위·아래로 단위 사이를 옮긴다', () => {
    const map = segmentMap({
      source_unit_count: 2,
      units: [
        segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
        segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
      ],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄\n둘째줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    const unit1 = screen.getByLabelText(/쉬운 글 단위 1/) as HTMLTextAreaElement
    const unit2 = screen.getByLabelText(/쉬운 글 단위 2/) as HTMLTextAreaElement

    unit1.focus()
    unit1.setSelectionRange(unit1.value.length, unit1.value.length)
    fireEvent.keyDown(unit1, { key: 'ArrowDown' })
    expect(unit2).toHaveFocus()

    unit2.setSelectionRange(0, 0)
    fireEvent.keyDown(unit2, { key: 'ArrowUp' })
    expect(unit1).toHaveFocus()
  })

  it('단위가 200개를 넘으면 단일 textarea로 내려앉고 배너가 사유를 적는다', () => {
    const unitCount = 201
    const bigText = Array.from({ length: unitCount }, (_, index) => `문장 ${index}`).join('\n')
    const map = segmentMap({
      source_unit_count: unitCount,
      units: Array.from({ length: unitCount }, (_, index) =>
        segmentMapUnit({
          easy_unit_index: index,
          source_unit_indexes: [index],
          confidence: 'high',
        }),
      ),
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: bigText, segment_map: map })}
        source={sourceFailed()}
      />,
    )

    expect(screen.getByText(/문단이 200개를 넘어/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/쉬운 글 단위 1,/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue(bigText)
    // 재변환은 이 슬라이스(S3) 범위 밖이다(S4) — 이 화면에서 그런 버튼을 만들지 않는다.
    expect(screen.queryByRole('button', { name: /재변환/ })).not.toBeInTheDocument()
  })

  /**
   * CRITICAL 리뷰: 단위 안에서 Enter만 `handleSplit`을 타고, Shift+Enter·붙여넣기·드롭은
   * 브라우저가 `\n`이 이미 섞인 값을 그대로 `onChange`로 준다. 이 값을 지도 갱신 없이
   * 받으면 `units = value.split('\n')`만 늘어나고 `unitMap`은 그대로 남아 그 뒤 모든
   * 단위가 엉뚱한 지도 항목을 입는다. 세 경로 모두 같은 방식(그 자리에서 나뉘는 것으로
   * 보고 지도를 함께 다시 짬)으로 막히는지, 그리고 밀려난 뒤 단위는 원래 대응을 그대로
   * 유지하는지 재는 자리다.
   */
  describe('단위 안에 줄바꿈이 섞여 들어오는 경로(Shift+Enter·붙여넣기·드롭)', () => {
    function renderTwoUnits() {
      const map = segmentMap({
        source_unit_count: 2,
        units: [
          segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' }),
          segmentMapUnit({ easy_unit_index: 1, source_unit_indexes: [1], confidence: 'high' }),
        ],
      })
      render(
        <ReviewEditor
          conversion={conversion({ easy_text: '첫줄\n둘째줄', segment_map: map })}
          source={sourceFailed()}
        />,
      )
      return screen.getByLabelText(/쉬운 글 단위 1/) as HTMLTextAreaElement
    }

    it('Shift+Enter로 줄바꿈이 섞여도 지도 길이가 단위 수와 같고 뒤 단위는 원래 대응을 유지한다', async () => {
      const user = userEvent.setup()
      const unit1 = renderTwoUnits()
      unit1.focus()
      unit1.setSelectionRange(1, 1)

      await user.keyboard('{Shift>}{Enter}{/Shift}')

      expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫')
      expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('줄')
      // 밀려난 뒤 단위는 원래 대응(원본 2번째 문단, high)을 그대로 유지한다 — 지도
      // 갱신이 다른 단위까지 어긋나게 만들지 않았다는 뜻이다.
      expect(screen.getByLabelText('쉬운 글 단위 3, 원본 2번째 문단에 대응')).toHaveValue('둘째줄')
    })

    it('붙여넣기로 줄바꿈이 섞여도 지도 길이가 단위 수와 같고 뒤 단위는 원래 대응을 유지한다', async () => {
      const user = userEvent.setup()
      const unit1 = renderTwoUnits()
      unit1.focus()
      unit1.setSelectionRange(1, 1)

      await user.paste('X\nY')

      expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫X')
      expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('Y줄')
      expect(screen.getByLabelText('쉬운 글 단위 3, 원본 2번째 문단에 대응')).toHaveValue('둘째줄')
    })

    it('드롭으로 줄바꿈이 섞여도 지도 길이가 단위 수와 같고 뒤 단위는 원래 대응을 유지한다', () => {
      const unit1 = renderTwoUnits()

      // jsdom은 드롭의 기본 삽입 동작(브라우저가 드롭 지점에 텍스트를 끼워 넣고 input을
      // 흘려보내는 것)을 구현하지 않는다 — 실제 브라우저가 만들 최종 DOM 상태(이미 섞인
      // 값)를 change로 직접 재현해, onChange 처리 로직 자체를 검증한다.
      fireEvent.drop(unit1, { dataTransfer: { getData: () => 'X\nY' } })
      fireEvent.change(unit1, { target: { value: '첫X\nY줄' } })

      expect(screen.getByLabelText('쉬운 글 단위 1, 원본 1번째 문단에 대응')).toHaveValue('첫X')
      expect(screen.getByLabelText('쉬운 글 단위 2, 대응 확인 불가')).toHaveValue('Y줄')
      expect(screen.getByLabelText('쉬운 글 단위 3, 원본 2번째 문단에 대응')).toHaveValue('둘째줄')
    })
  })
})

describe('저장 중 경합 방지(MEDIUM 리뷰)', () => {
  it('저장·내려받기가 도는 동안 단위 textarea를 잠근다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockReturnValue(new Promise<ConversionResponse>(() => undefined))
    const map = segmentMap({
      units: [segmentMapUnit({ easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high' })],
    })
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '첫줄', segment_map: map })}
        source={sourceFailed()}
      />,
    )

    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    expect(screen.getByLabelText(/쉬운 글 단위 1/)).toBeDisabled()
  })

  it('저장이 도는 동안 이어서 고치면 응답이 그 사이의 수정을 덮어쓰지 않는다', async () => {
    const user = userEvent.setup()
    let resolveSave: (value: ConversionResponse) => void = () => undefined
    vi.mocked(saveReview).mockReturnValue(
      new Promise<ConversionResponse>((resolve) => {
        resolveSave = resolve
      }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안' })} source={sourceFailed()} />)

    const textarea = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)') as HTMLTextAreaElement
    await user.click(screen.getByRole('button', { name: '검수 내용 저장' }))

    // 저장 응답이 오기 전에 이어서 고친다. 지금 이 입력칸은 busy 동안 disabled지만,
    // 여기서는 응답 처리 로직 자체(persistDraft의 낡은 응답 판정)를 검증하려고 값
    // 변경을 직접 흘려보낸다.
    fireEvent.change(textarea, { target: { value: '이어서 고친 글' } })

    resolveSave(conversion({ edited_text: '초안', reviewed_at: '2026-08-07T02:00:00Z' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '검수 내용 저장' })).not.toBeDisabled(),
    )

    // 응답(옛 초안 기준)이 그 사이에 고친 글을 덮어쓰지 않았다.
    expect(textarea).toHaveValue('이어서 고친 글')
  })
})

/**
 * 원문 패널(읽기 전용)에서도 사전 조회는 되고 적용 버튼은 없다(계획 §3.5, HIGH 리뷰 1).
 * `TermLookupPopover`는 결과 패널뿐 아니라 원문 패널 컨테이너에도 별도 인스턴스로 붙는다.
 */
describe('원문 패널의 사전 조회', () => {
  it('원문에서 선택하면 팝업에 후보가 뜨지만 바꾸기 버튼은 없다', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(lookupTerm).mockResolvedValue({
        query: '구비서류',
        candidates: [
          {
            term: '구비서류',
            easy_term: '준비할 서류',
            strategy: 'substitute',
            risk: 'none',
            definition: '신청할 때 미리 갖춰야 하는 서류',
            caution: null,
            tags: [],
            examples: [],
            match_kind: 'exact',
            applicable: true,
          },
        ],
        dictionary: { name: '쉬운 말 사전', license: 'CC-BY', schema_version: '1.0.0' },
      })
      render(
        <ReviewEditor
          conversion={conversion({ easy_text: '결과 글', edited_text: null })}
          source={sourceReady('구비서류를 준비하세요')}
        />,
      )
      const sourceTextarea = screen.getByLabelText('원본 (읽기 전용)') as HTMLTextAreaElement
      sourceTextarea.focus()
      sourceTextarea.setSelectionRange(0, 4) // "구비서류"
      fireEvent.mouseUp(sourceTextarea)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(250)
      })

      expect(screen.getByRole('dialog', { name: '쉬운 말 후보' })).toBeInTheDocument()
      expect(screen.getByText('준비할 서류')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '바꾸기' })).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})
