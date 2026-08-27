import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, downloadExport, saveReview } from '../api/client'
import type { FormatPreservation } from '../api/types'
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

    await user.click(screen.getByRole('button', { name: 'TXT로 내려받기' }))

    await screen.findByText('TXT 파일을 내려받았습니다.')
    expect(screen.getByRole('button', { name: 'TXT로 내려받기' })).toHaveFocus()
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
    render(<ReviewEditor conversion={conversion({ export_format: 'docx' })} sourceText={null} />)

    expect(screen.getByRole('button', { name: 'DOCX로 내려받기' })).toBeInTheDocument()
    // 원본이 DOCX인데 txt·hwpx 버튼을 그리면 그 버튼은 서버에서 반드시 409로 실패한다.
    expect(screen.queryByRole('button', { name: 'TXT로 내려받기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'HWPX로 내려받기' })).not.toBeInTheDocument()
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
        sourceText={null}
      />,
    )

    const panel = screen.getByRole('region', { name: '원본 서식 유지' })
    expect(within(panel).getByText(label)).toBeInTheDocument()
    expect(within(panel).getByText('유지 가능')).toBeInTheDocument()
  })

  /** §6.5 표 — 붙여넣기는 「적용 대상 아님」이라 패널 자체가 없다. */
  it('붙여넣기(TXT)에는 패널을 그리지 않는다', () => {
    render(<ReviewEditor conversion={conversion()} sourceText="원문입니다." />)

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
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
  it('PDF 원본에서는 상태 표시 대신 내려받기가 없는 이유를 말한다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ source_format: 'pdf', export_format: null })}
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
        sourceText={null}
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
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
    render(<ReviewEditor conversion={conversion()} sourceText={null} />)

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
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
    render(<ReviewEditor conversion={conversion({ easy_text: '초안.' })} sourceText={null} />)

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
