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

beforeEach(() => {
  vi.mocked(saveReview).mockReset()
  vi.mocked(downloadExport).mockReset()
})

afterEach(() => {
  // 모듈 전역 상태라 테스트끼리 새지 않게 되돌린다(언마운트 정리와 같은 일).
  setUnsavedChanges(false)
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
    // 원본이 없는 경로임을 숨기지 않는다.
    expect(screen.getByText(/이 화면에서는 원본을 볼 수 없습니다/)).toBeInTheDocument()
  })

  it('AI 초안임을 알리는 배너와 자리표시자 유실 경고를 보여준다', () => {
    render(
      <ReviewEditor
        conversion={conversion({ missing_placeholders: ['[[이메일1]]'] })}
        sourceText={null}
      />,
    )

    expect(screen.getByRole('note')).toHaveTextContent('AI가 만든 초안입니다')
    expect(screen.getByText(/\[\[이메일1\]\]가 결과에서 빠졌습니다/)).toBeInTheDocument()
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
    // 저장이 끝나면 "저장하지 않은 수정" 안내가 사라진다.
    expect(screen.getByText(/마지막 저장:/)).toBeInTheDocument()
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
  })

  it('docx 내려받기를 누르면 파일을 받아 저장한다', async () => {
    const user = userEvent.setup()
    const objectUrl = 'blob:test'
    const createObjectURL = vi.fn(() => objectUrl)
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.mocked(downloadExport).mockResolvedValue({
      blob: new Blob(['내용']),
      filename: '재난지원금 안내.docx',
    })
    render(<ReviewEditor conversion={conversion()} sourceText={null} />)

    await user.click(screen.getByRole('button', { name: 'docx 내려받기' }))

    expect(vi.mocked(downloadExport)).toHaveBeenCalledWith('c1', 'docx')
    expect(click).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith(objectUrl)
    expect(await screen.findByText('DOCX 파일을 내려받았습니다.')).toBeInTheDocument()

    click.mockRestore()
    vi.unstubAllGlobals()
  })

  it('마스킹 항목이 결과에 남아 있는지 표로 알려준다', async () => {
    const user = userEvent.setup()
    render(
      <ReviewEditor
        conversion={conversion({ easy_text: '전화는 [[전화번호1]]이에요.' })}
        sourceText={null}
      />,
    )

    expect(screen.getByRole('row', { name: /전화번호1/ })).toHaveTextContent('있음')

    // 검수하다 자리표시자를 지우면 그 사실이 표에 바로 드러나야 한다.
    await user.clear(screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)'))

    expect(screen.getByRole('row', { name: /전화번호1/ })).toHaveTextContent('없음')
  })
})
