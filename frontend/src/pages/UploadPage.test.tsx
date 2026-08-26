import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, createDocumentFromFile, createDocumentFromText } from '../api/client'
import { workspaceContext, workspaceItem } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import type { WorkspaceContextValue } from '../workspace/context'
import { ACCEPTED_EXTENSIONS, MAX_CHARS, MAX_UPLOAD_BYTES, UploadPage } from './UploadPage'

/** 화면이 안내에 쓰는 값은 상수에서 나와야 한다 — 테스트도 같은 상수에서 기대값을 만든다. */
const EXPECTED_FORMATS = ACCEPTED_EXTENSIONS.split(',')
  .map((extension) => extension.replace('.', '').toUpperCase())
  .join(' · ')

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

/** 2KB짜리 docx. jsdom의 File.size는 넘긴 내용의 바이트 수다. */
function docxFile(name = '안내문.docx'): File {
  return new File(['x'.repeat(2048)], name, { type: DOCX_TYPE })
}

/** 파일 올리기 모드로 바꾸고 파일 입력을 돌려준다. */
async function chooseFileMode(user: ReturnType<typeof userEvent.setup>): Promise<HTMLInputElement> {
  await user.click(screen.getByRole('radio', { name: '파일 올리기' }))
  return screen.getByLabelText('바꿀 파일') as HTMLInputElement
}

vi.mock('../api/client', async (importOriginal) => ({
  // ApiError는 화면이 instanceof로 가르므로 진짜 클래스를 그대로 쓴다.
  ...(await importOriginal<typeof import('../api/client')>()),
  createDocumentFromText: vi.fn(),
  createDocumentFromFile: vi.fn(),
}))

function renderPage(workspace: Partial<WorkspaceContextValue> = {}) {
  return render(
    <WorkspaceContext.Provider value={workspaceContext(workspace)}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<UploadPage />} />
          <Route path="/conversions/:conversionId" element={<h2>변환 화면</h2>} />
        </Routes>
      </MemoryRouter>
    </WorkspaceContext.Provider>,
  )
}

beforeEach(() => {
  vi.mocked(createDocumentFromText).mockReset()
  vi.mocked(createDocumentFromFile).mockReset()
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

    await user.type(screen.getByLabelText('문서 제목'), '청년 월세 지원 안내')
    await user.type(screen.getByLabelText('바꿀 글'), '신청 안내')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(vi.mocked(createDocumentFromText)).toHaveBeenCalledWith(
      '신청 안내',
      'w1',
      '청년 월세 지원 안내',
    )
    expect(await screen.findByRole('heading', { name: '변환 화면' })).toBeInTheDocument()
  })

  it('지금 고른 작업 공간에 담는다', async () => {
    const user = userEvent.setup()
    vi.mocked(createDocumentFromText).mockResolvedValue({
      document_id: 'd1',
      conversion_id: 'c1',
      status: 'pending',
      char_count: 7,
    })
    renderPage({
      workspaces: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
      currentId: 'w2',
    })

    await user.type(screen.getByLabelText('문서 제목'), '민원 안내')
    await user.type(screen.getByLabelText('바꿀 글'), '신청 안내')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(vi.mocked(createDocumentFromText)).toHaveBeenCalledWith('신청 안내', 'w2', '민원 안내')
  })

  it('작업 공간을 아직 못 받았어도 올릴 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(createDocumentFromText).mockResolvedValue({
      document_id: 'd1',
      conversion_id: 'c1',
      status: 'pending',
      char_count: 7,
    })
    renderPage({ workspaces: [], currentId: null })

    await user.type(screen.getByLabelText('문서 제목'), '기본 작업 공간 문서')
    await user.type(screen.getByLabelText('바꿀 글'), '신청 안내')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    // null이면 서버가 기본 작업 공간에 담는다 — 업로드를 막지 않는다.
    expect(vi.mocked(createDocumentFromText)).toHaveBeenCalledWith(
      '신청 안내',
      null,
      '기본 작업 공간 문서',
    )
  })

  it('상한을 넘은 글은 서버에 보내지 않고 알린다', async () => {
    const user = userEvent.setup()
    renderPage()

    // 4,000자 상한을 넘긴다. 붙여넣기(paste)로 넣어야 한 글자씩 타이핑하지 않는다.
    await user.click(screen.getByLabelText('바꿀 글'))
    await user.paste('가'.repeat(4001))
    await user.type(screen.getByLabelText('문서 제목'), '긴 원문')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('4,000자 이내로 줄여 주세요')
    // 글자 수 안내는 같은 사실을 두 번 알리지 않는다(라이브 영역이 아니다).
    expect(screen.getByLabelText('바꿀 글')).toHaveAttribute('aria-invalid', 'true')
    expect(vi.mocked(createDocumentFromText)).not.toHaveBeenCalled()
  })

  it('제목 없이는 서버에 보내지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('바꿀 글'), '신청 안내')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('문서 제목을 입력해 주세요.')
    expect(vi.mocked(createDocumentFromText)).not.toHaveBeenCalled()
  })

  it('서버가 거절하면 그 사유를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(createDocumentFromText).mockRejectedValue(
      new ApiError(422, '변환할 수 있는 길이를 넘었습니다'),
    )
    renderPage()

    await user.type(screen.getByLabelText('문서 제목'), '긴 문서')
    await user.type(screen.getByLabelText('바꿀 글'), '긴 문서')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('변환할 수 있는 길이를 넘었습니다')
    expect(screen.queryByRole('heading', { name: '변환 화면' })).not.toBeInTheDocument()
  })

  it('현재 작업 공간을 헤더 맥락 라벨에 보여준다', () => {
    renderPage({ workspaces: [workspaceItem({ id: 'w1', name: '복지정책팀' })], currentId: 'w1' })

    expect(screen.getByText('복지정책팀 · 새 변환')).toBeInTheDocument()
  })

  it('파일을 고르면 파일명·크기·형식을 카드로 보여준다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    await user.upload(input, docxFile())

    expect(screen.getByText('안내문.docx')).toBeInTheDocument()
    expect(screen.getByText('DOCX · 2KB')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '안내문.docx 파일 제거' })).toBeInTheDocument()
  })

  it('제거하면 카드가 사라지고 같은 파일을 다시 고를 수 있다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    await user.upload(input, docxFile())
    await user.click(screen.getByRole('button', { name: '안내문.docx 파일 제거' }))

    expect(screen.queryByText('안내문.docx')).not.toBeInTheDocument()
    // 상태만 비우고 DOM 값을 남기면 브라우저가 "값이 그대로"라고 보아 change를 내지
    // 않는다 — 방금 지운 파일을 다시 고르지 못하는 흔한 고장이다.
    expect(input.value).toBe('')

    await user.upload(input, docxFile())
    expect(screen.getByText('안내문.docx')).toBeInTheDocument()
  })

  it('제거한 파일은 제출에도 쓰이지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    await user.upload(input, docxFile())
    await user.click(screen.getByRole('button', { name: '안내문.docx 파일 제거' }))
    await user.type(screen.getByLabelText('문서 제목'), '안내문')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('변환할 파일을 선택해 주세요.')
    expect(vi.mocked(createDocumentFromFile)).not.toHaveBeenCalled()
  })

  it('글자 수는 상한의 80% 미만이면 보조색, 그 이상이면 주의색으로 알린다', async () => {
    const user = userEvent.setup()
    renderPage()

    const threshold = MAX_CHARS * 0.8
    await user.click(screen.getByLabelText('바꿀 글'))
    await user.paste('가'.repeat(threshold - 1))

    const belowCounter = screen.getByText(`${(threshold - 1).toLocaleString('ko-KR')} / 4,000자`)
    expect(belowCounter).toHaveClass('text-muted-foreground')
    expect(belowCounter).not.toHaveClass('text-warning')

    await user.paste('가')

    const atCounter = screen.getByText(`${threshold.toLocaleString('ko-KR')} / 4,000자`)
    expect(atCounter).toHaveClass('text-warning')
    // 80%는 아직 오류가 아니다 — 입력은 유효한 상태로 남는다.
    expect(screen.getByLabelText('바꿀 글')).toHaveAttribute('aria-invalid', 'false')
  })

  it('안내 카드의 지원 형식·크기는 코드 상수에서 나온다', () => {
    renderPage()

    const guide = screen.getByRole('region', { name: '이 작업에서 일어나는 일' })
    expect(within(guide).getByText(EXPECTED_FORMATS)).toBeInTheDocument()
    expect(
      within(guide).getByText(`${(MAX_UPLOAD_BYTES / 1024 / 1024).toLocaleString('ko-KR')}MB 이내`),
    ).toBeInTheDocument()
    expect(
      within(guide).getByText(`한 번에 ${MAX_CHARS.toLocaleString('ko-KR')}자까지`),
    ).toBeInTheDocument()
  })

  it('안내 카드는 개인정보 2종만 가린다고 알린다', () => {
    renderPage()

    const guide = screen.getByRole('region', { name: '이 작업에서 일어나는 일' })
    expect(within(guide).getByText('개인정보 2종 가림')).toBeInTheDocument()
    expect(within(guide).getByText(/주민등록번호와 카드번호/)).toBeInTheDocument()
    // 계약이 가리는 범주는 2종뿐이다 — 없는 보호를 약속하지 않는다.
    expect(within(guide).queryByText(/전화번호|이메일/)).not.toBeInTheDocument()
  })
})
