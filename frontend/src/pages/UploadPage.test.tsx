import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  createDocumentFromFile,
  createDocumentFromText,
  listDocuments,
} from '../api/client'
import type { DocumentListItem } from '../api/types'
import { documentItem, workspaceContext, workspaceItem } from '../test/factories'
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

/**
 * 브라우저가 파일 선택 대화상자를 닫는 순서를 그대로 흉내 낸다.
 *
 * 실제 브라우저는 **초점을 파일 입력에 되돌려 놓은 뒤** change를 알린다 — 그래서 change를
 * 받아 그 입력을 감추면 초점이 갈 곳을 잃는다. `userEvent.upload()`는 반대로 change를 낸
 * 다음 입력을 다시 focus 하므로(user-event의 `behavior.click`) 이 순서를 재현하지 못한다.
 * 초점이 어디로 가는지를 재는 테스트에서만 이 도우미를 쓴다.
 */
function selectFileLikeBrowser(input: HTMLInputElement, file: File) {
  input.focus()
  fireEvent.change(input, { target: { files: [file] } })
}

vi.mock('../api/client', async (importOriginal) => ({
  // ApiError는 화면이 instanceof로 가르므로 진짜 클래스를 그대로 쓴다.
  ...(await importOriginal<typeof import('../api/client')>()),
  createDocumentFromText: vi.fn(),
  createDocumentFromFile: vi.fn(),
  listDocuments: vi.fn(),
}))

/** GET /documents 한 쪽. 「다음 할 일」은 이 응답만 근거로 삼는다(§7). */
function documentPage(items: DocumentListItem[]) {
  return { items, limit: 20, offset: 0, has_more: false }
}

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
  // 기본값은 "문서 없음"이다 — 그 경우 이 화면은 아무것도 제안하지 않으므로(제안이
  // 곧 이 화면의 대표 행동과 같은 말이 된다) 나머지 테스트가 제안에 영향받지 않는다.
  vi.mocked(listDocuments).mockReset().mockResolvedValue(documentPage([]))
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

  it('파일을 고른 뒤 초점이 <body>로 떨어지지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    selectFileLikeBrowser(input, docxFile())

    // 대화상자가 닫힌 직후 초점을 갖고 있는 것은 방금 고른 그 파일 입력이다. 파일을
    // 고르면 그 입력이 `display:none`이 되므로, 화면이 초점을 옮겨 두지 않으면 초점은
    // <body>로 떨어진다 — 키보드 사용자는 Tab 위치를 잃고 낭독기는 무엇이 선택됐는지도,
    // 새로 나타난 카드도 알리지 못한다. 그렇게 감추기만 하는 리팩터가 다시 들어오면
    // 여기서 걸려야 한다.
    expect(document.activeElement).not.toBe(document.body)
    // jsdom은 CSS를 적용하지 않아 감춘 입력도 계속 초점을 받을 수 있다. 그래서 위
    // 단언만으로는 부족하고 「감춰진 입력에 초점이 남아 있지 않다」까지 함께 못박는다.
    expect(input).not.toHaveFocus()
  })

  it('파일을 고르면 초점이 선택한 파일 카드로 옮겨간다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    selectFileLikeBrowser(input, docxFile())

    // 초점이 옮겨가는 것만으로 낭독기가 "선택한 파일 안내문.docx"를 읽는다 — 제거
    // 버튼에 초점을 주면 "제거"만 읽혀 무슨 파일인지 알 수 없다.
    const card = screen.getByRole('group', { name: '선택한 파일 안내문.docx' })
    expect(card).toHaveFocus()
    // 초점만 받고 탭 순서에는 끼지 않는다.
    expect(card).toHaveAttribute('tabindex', '-1')
  })

  it('제거하면 초점이 다시 파일 입력으로 돌아온다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    await user.upload(input, docxFile())
    await user.click(screen.getByRole('button', { name: '안내문.docx 파일 제거' }))

    // 방금 누른 제거 버튼이 사라졌다. 선택 경로가 카드로 옮긴 초점이 이 복귀를 가로채면
    // 안 된다 — 두 경로는 서로 다른 표시를 보고 각자의 자리로만 초점을 옮긴다.
    expect(input).toHaveFocus()
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

  it('제안은 한 건만, 대표 행동보다 아래에 보여준다', async () => {
    // 네 조건이 모두 있는 목록이다. 규칙의 우선순위는 nextAction.test.ts가 고정하고,
    // 여기서는 "화면에 하나만 나온다"만 본다.
    vi.mocked(listDocuments).mockResolvedValue(
      documentPage([
        documentItem({ id: 'd1', conversion_id: 'c1', status: 'failed', title: '실패 문서' }),
        documentItem({ id: 'd2', conversion_id: 'c2', status: 'processing', title: '진행 문서' }),
        documentItem({
          id: 'd3',
          conversion_id: 'c3',
          status: 'done',
          reviewed_at: null,
          title: '초안 문서',
        }),
      ]),
    )
    renderPage()

    const suggestion = await screen.findByRole('complementary', { name: '다음 할 일' })
    expect(within(suggestion).getAllByRole('link')).toHaveLength(1)
    expect(within(suggestion).getByText('쉬운 글 초안을 검수해 주세요')).toBeInTheDocument()
    expect(within(suggestion).getByRole('link', { name: '‘초안 문서’ 검수 열기' })).toHaveAttribute(
      'href',
      '/conversions/c3',
    )

    // 대표 행동은 여전히 제출 버튼 하나뿐이다(§5.3, §14) — 제안은 링크로만 나타난다.
    expect(screen.getAllByRole('button', { name: '쉬운 글 초안 만들기' })).toHaveLength(1)
    expect(within(suggestion).queryByRole('button')).not.toBeInTheDocument()
  })

  it('목록 조회가 실패해도 문서 등록은 그대로 동작한다', async () => {
    const user = userEvent.setup()
    vi.mocked(listDocuments).mockRejectedValue(new ApiError(500, '문서를 불러오지 못했습니다'))
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

    expect(await screen.findByRole('heading', { name: '변환 화면' })).toBeInTheDocument()
    // 보조 제안이 실패한 것을 오류로 알리지 않는다 — 핵심 흐름을 가리는 소음이다.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: '다음 할 일' })).not.toBeInTheDocument()
  })

  it('이 화면이 곧 새 변환이므로 문서가 없으면 아무것도 제안하지 않는다', async () => {
    renderPage()

    expect(await screen.findByRole('button', { name: '쉬운 글 초안 만들기' })).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: '다음 할 일' })).not.toBeInTheDocument()
  })

  it('제안 근거는 지금 고른 작업 공간으로 좁혀 조회한다', async () => {
    renderPage({
      workspaces: [workspaceItem({ id: 'w1' }), workspaceItem({ id: 'w2', name: '민원 안내' })],
      currentId: 'w2',
    })

    await screen.findByRole('button', { name: '쉬운 글 초안 만들기' })
    expect(vi.mocked(listDocuments)).toHaveBeenCalledWith(
      expect.objectContaining({ workspaceId: 'w2' }),
      expect.anything(),
    )
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
