import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, createDocumentFromText } from '../api/client'
import { UploadPage } from './UploadPage'

vi.mock('../api/client', async (importOriginal) => ({
  // ApiError는 화면이 instanceof로 가르므로 진짜 클래스를 그대로 쓴다.
  ...(await importOriginal<typeof import('../api/client')>()),
  createDocumentFromText: vi.fn(),
  createDocumentFromFile: vi.fn(),
}))

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<UploadPage />} />
        <Route path="/conversions/:conversionId" element={<h2>변환 화면</h2>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.mocked(createDocumentFromText).mockReset()
})

describe('업로드 화면', () => {
  it('붙여넣은 글을 올리고 변환 화면으로 넘어간다', async () => {
    const user = userEvent.setup()
    vi.mocked(createDocumentFromText).mockResolvedValue({
      document_id: 'd1',
      conversion_id: 'c1',
      status: 'pending',
      char_count: 7,
    })
    renderPage()

    await user.type(screen.getByLabelText('바꿀 글'), '신청 안내')
    await user.click(screen.getByRole('button', { name: '쉬운 글로 바꾸기' }))

    expect(vi.mocked(createDocumentFromText)).toHaveBeenCalledWith('신청 안내')
    expect(await screen.findByRole('heading', { name: '변환 화면' })).toBeInTheDocument()
  })

  it('상한을 넘은 글은 서버에 보내지 않고 알린다', async () => {
    const user = userEvent.setup()
    renderPage()

    // 4,000자 상한을 넘긴다. 붙여넣기(paste)로 넣어야 한 글자씩 타이핑하지 않는다.
    await user.click(screen.getByLabelText('바꿀 글'))
    await user.paste('가'.repeat(4001))
    await user.click(screen.getByRole('button', { name: '쉬운 글로 바꾸기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('4,000자 이내로 줄여 주세요')
    // 글자 수 안내는 같은 사실을 두 번 알리지 않는다(라이브 영역이 아니다).
    expect(screen.getByLabelText('바꿀 글')).toHaveAttribute('aria-invalid', 'true')
    expect(vi.mocked(createDocumentFromText)).not.toHaveBeenCalled()
  })

  it('서버가 거절하면 그 사유를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(createDocumentFromText).mockRejectedValue(
      new ApiError(422, '변환할 수 있는 길이를 넘었습니다'),
    )
    renderPage()

    await user.type(screen.getByLabelText('바꿀 글'), '긴 문서')
    await user.click(screen.getByRole('button', { name: '쉬운 글로 바꾸기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('변환할 수 있는 길이를 넘었습니다')
    expect(screen.queryByRole('heading', { name: '변환 화면' })).not.toBeInTheDocument()
  })
})
