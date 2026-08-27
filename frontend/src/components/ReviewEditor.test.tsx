import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, downloadExport, saveReview } from '../api/client'
import { setUnsavedChanges } from '../review/unsavedChanges'
import { conversion } from '../test/factories'
import { ReviewEditor } from './ReviewEditor'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  saveReview: vi.fn(),
  downloadExport: vi.fn(),
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
})

afterEach(() => {
  // 모듈 전역 상태라 테스트끼리 새지 않게 되돌린다(언마운트 정리와 같은 일).
  setUnsavedChanges(false)
  vi.unstubAllGlobals()
})

describe('검수 에디터', () => {
  it('저장한 수정본이 있으면 그것을 초기값으로 쓴다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: 'AI 초안입니다.', edited_text: '담당자가 고친 글.' })}
        sourceText="원문입니다."
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
        sourceText={null}
      />,
    )

    expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toHaveValue('AI 초안입니다.')
  })

  /**
   * 원문이 없는 경로(파일 업로드·기록 재진입)에서 왼쪽에 빈 입력칸이 남아 있으면,
   * 화면은 "원문을 보여주지 않는다"가 아니라 "원문이 아직 안 왔다" 또는 "여기에 원문을
   * 적어야 한다"고 말하게 된다. 셋은 서로 다른 상태이므로(DESIGN.md §9) 입력칸이
   * 사라졌는지와 설명이 남았는지를 함께 고정한다.
   */
  it('원문이 없으면 빈 입력칸 대신 설명 카드를 보여준다', () => {
    render(<ReviewEditor conversion={conversion()} sourceText={null} />)

    expect(screen.queryByLabelText('원본 (읽기 전용)')).not.toBeInTheDocument()
    expect(
      screen.getByText('파일로 올린 문서는 이 화면에서 원문을 다시 표시하지 않습니다.'),
    ).toBeInTheDocument()
  })

  it('AI 초안임을 알리는 배너와 자리표시자 유실 경고를 보여준다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ missing_placeholders: ['[[카드번호1]]'] })}
        sourceText={null}
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
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
    저장 버튼은 진행 중에 `disabled` 가 된다. 브라우저는 초점을 가진 요소가 잠기는 순간
    초점을 `<body>` 로 떨어뜨리므로, 되돌려 놓지 않으면 키보드 사용자는 저장 한 번에
    탭 경로를 통째로 잃고 문서 맨 앞에서 다시 밟아야 한다(§14).
  */
  it('저장이 끝나면 초점이 저장 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '초안. 수정', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
    render(<ReviewEditor conversion={conversion()} sourceText={null} />)

    await user.click(screen.getByRole('button', { name: 'txt 내려받기' }))

    await screen.findByText('TXT 파일을 내려받았습니다.')
    expect(screen.getByRole('button', { name: 'txt 내려받기' })).toHaveFocus()
  })

  it('수정한 글을 저장하고 결과를 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(saveReview).mockResolvedValue(
      conversion({ edited_text: '고친 글.', reviewed_at: '2026-08-07T02:00:00Z' }),
    )
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
      render(<ReviewEditor conversion={conversion({ export_format: format })} sourceText={null} />)

      await user.click(screen.getByRole('button', { name: `${format} 내려받기` }))

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
    render(<ReviewEditor conversion={conversion({ export_format: 'docx' })} sourceText={null} />)

    expect(screen.getByRole('button', { name: 'docx 내려받기' })).toBeInTheDocument()
    // 원본이 DOCX인데 txt·hwpx 버튼을 그리면 그 버튼은 서버에서 반드시 409로 실패한다.
    expect(screen.queryByRole('button', { name: 'txt 내려받기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'hwpx 내려받기' })).not.toBeInTheDocument()
  })

  it('내려받을 수단이 없으면(export_format null) 내려받기 행동을 제시하지 않는다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: null })}
        sourceText={null}
      />,
    )

    // §6.5 — 상태를 지어내지 않는다. 저장은 여전히 할 수 있어야 한다.
    expect(screen.getByRole('button', { name: '검수 내용 저장' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /내려받기$/ })).not.toBeInTheDocument()
  })

  it('마스킹 항목이 결과에 남아 있는지 표로 알려준다', async () => {
    const user = userEvent.setup()
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '등록번호는 [[주민등록번호1]]이에요.' })}
        sourceText={null}
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
      render(<ReviewEditor conversion={conversion()} sourceText="원문입니다." />)

      expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['원문', '쉬운 글'])
      expect(screen.getByLabelText('원본 (읽기 전용)')).toBeVisible()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).not.toBeVisible()

      screen.getByRole('tab', { name: '원문' }).focus()
      await user.keyboard('{ArrowRight}')

      expect(screen.getByRole('tab', { name: '쉬운 글' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
      expect(screen.getByLabelText('원본 (읽기 전용)')).not.toBeVisible()
    })

    it('원문이 없으면 탭을 만들지 않고 설명 카드를 그대로 보여준다', () => {
      stubViewport(false)
      render(<ReviewEditor conversion={conversion()} sourceText={null} />)

      expect(screen.queryAllByRole('tab')).toHaveLength(0)
      expect(
        screen.getByText('파일로 올린 문서는 이 화면에서 원문을 다시 표시하지 않습니다.'),
      ).toBeInTheDocument()
      expect(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')).toBeVisible()
    })
  })
})
