import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { DownloadFormatDialog } from './DownloadFormatDialog'

function setup(props: Partial<Parameters<typeof DownloadFormatDialog>[0]> = {}) {
  const onClose = vi.fn()
  const onConfirm = vi.fn()
  render(
    <DownloadFormatDialog
      open
      formats={['hwpx', 'docx', 'txt']}
      dirty={false}
      onClose={onClose}
      onConfirm={onConfirm}
      {...props}
    />,
  )
  return { onClose, onConfirm }
}

describe('DownloadFormatDialog', () => {
  it('제목·형식 라디오·설명을 그리고 첫 형식을 고른 채 시작한다', () => {
    setup()

    expect(screen.getByRole('dialog', { name: '내려받을 형식' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '형식' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(3)
    expect(screen.getByRole('radio', { name: /HWPX/ })).toBeChecked()
    expect(screen.getByText('한글 문서 · 원본 서식을 최대한 유지')).toBeInTheDocument()
    expect(screen.getByText('글만 · 서식 없음')).toBeInTheDocument()
    expect(screen.getByText('내려받을 파일 형식을 고르세요.')).toBeInTheDocument()
  })

  it('저장하지 않은 수정이 있으면 저장 후 내려받는다고 말하고 확인 버튼 이름에 싣는다', () => {
    setup({ dirty: true })

    expect(
      screen.getByText('저장하지 않은 수정이 있으면 먼저 저장한 뒤 내려받습니다.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '저장하고 HWPX로 내려받기' })).toBeInTheDocument()
  })

  it('고른 형식으로 확인하면 그 형식을 넘긴다', async () => {
    const user = userEvent.setup()
    const { onConfirm } = setup()

    await user.click(screen.getByRole('radio', { name: /TXT/ }))
    await user.click(screen.getByRole('button', { name: 'TXT로 내려받기' }))

    expect(onConfirm).toHaveBeenCalledWith('txt')
  })

  it('취소와 Esc는 닫기만 한다', async () => {
    const user = userEvent.setup()
    const { onClose, onConfirm } = setup()

    await user.click(screen.getByRole('button', { name: '취소' }))
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(2)
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('다시 열면 첫 형식으로 돌아온다', async () => {
    const user = userEvent.setup()
    const props = {
      formats: ['docx', 'txt'] as const,
      dirty: false,
      onClose: vi.fn(),
      onConfirm: vi.fn(),
    }
    const { rerender } = render(<DownloadFormatDialog open {...props} />)
    await user.click(screen.getByRole('radio', { name: /TXT/ }))
    rerender(<DownloadFormatDialog open={false} {...props} />)
    rerender(<DownloadFormatDialog open {...props} />)

    expect(screen.getByRole('radio', { name: /DOCX/ })).toBeChecked()
  })
})
