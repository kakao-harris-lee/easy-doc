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
import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import {
  authContextValue,
  documentItem,
  userResponse,
  workspaceContext,
  workspaceItem,
} from '../test/factories'
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

/** 2KB짜리 pdf. 내용은 보지 않는다 — 화면은 확장자만 보고 안내를 고른다. */
function pdfFile(name = '안내문.pdf'): File {
  return new File(['x'.repeat(2048)], name, { type: 'application/pdf' })
}

/** 2KB짜리 txt. 붙여넣기(`text`)와 달리 업로드한 평문 파일이다. */
function txtFile(name = '안내문.txt'): File {
  return new File(['x'.repeat(2048)], name, { type: 'text/plain' })
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

/** 화면 한 벌. 작업 공간이 바뀌는 상황을 재려면 같은 트리를 다시 그려야 한다. */
function page(
  workspace: Partial<WorkspaceContextValue> = {},
  auth: Partial<AuthContextValue> = {},
) {
  return (
    <AuthContext.Provider value={authContextValue(auth)}>
      <WorkspaceContext.Provider value={workspaceContext(workspace)}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route path="/" element={<UploadPage />} />
            <Route path="/conversions/:conversionId" element={<h2>변환 화면</h2>} />
            <Route path="/verify-email" element={<h2>이메일 인증 화면</h2>} />
          </Routes>
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthContext.Provider>
  )
}

function renderPage(
  workspace: Partial<WorkspaceContextValue> = {},
  auth: Partial<AuthContextValue> = {},
) {
  return render(page(workspace, auth))
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

    // 20,000자 상한을 넘긴다. 붙여넣기(paste)로 넣어야 한 글자씩 타이핑하지 않는다.
    await user.click(screen.getByLabelText('바꿀 글'))
    await user.paste('가'.repeat(20001))
    await user.type(screen.getByLabelText('문서 제목'), '긴 원문')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('20,000자 이내로 줄여 주세요')
    // 글자 수 안내는 같은 사실을 두 번 알리지 않는다(라이브 영역이 아니다).
    expect(screen.getByLabelText('바꿀 글')).toHaveAttribute('aria-invalid', 'true')
    expect(vi.mocked(createDocumentFromText)).not.toHaveBeenCalled()
  })

  it('surrogate pair 문자(이모지) 2만 자는 코드 포인트 기준으로 상한 이내다', async () => {
    // 백엔드는 유니코드 코드 포인트로 상한을 잰다(DocumentLimits.charCountOf). '😀'는
    // UTF-16으로 2 코드 유닛(surrogate pair)이라 text.length로 세면 4만으로 잘못
    // 잡혀 상한을 넘겼다고 오판한다 — 코드 포인트 2만 개는 실제로는 상한 이내다.
    const user = userEvent.setup()
    vi.mocked(createDocumentFromText).mockResolvedValue({
      document_id: 'd1',
      conversion_id: 'c1',
      status: 'pending',
      char_count: 20000,
    })
    renderPage()

    const emoji20000 = '😀'.repeat(20000)
    await user.click(screen.getByLabelText('바꿀 글'))
    await user.paste(emoji20000)
    await user.type(screen.getByLabelText('문서 제목'), '이모지 문서')

    expect(screen.getByText('20,000 / 20,000자')).toBeInTheDocument()
    expect(screen.getByLabelText('바꿀 글')).toHaveAttribute('aria-invalid', 'false')

    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(vi.mocked(createDocumentFromText)).toHaveBeenCalledWith(emoji20000, 'w1', '이모지 문서')
  })

  it('surrogate pair 문자(이모지)가 코드 포인트 기준으로 상한을 넘으면 막는다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByLabelText('바꿀 글'))
    await user.paste('😀'.repeat(20001))
    await user.type(screen.getByLabelText('문서 제목'), '이모지 문서')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('20,000자 이내로 줄여 주세요')
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

  it('txt 파일도 올릴 수 있고 파일 등록으로 제출된다', async () => {
    const user = userEvent.setup()
    vi.mocked(createDocumentFromFile).mockResolvedValue({
      document_id: 'd1',
      conversion_id: 'c1',
      status: 'pending',
      char_count: 7,
    })
    renderPage()

    const input = await chooseFileMode(user)
    await user.upload(input, txtFile())

    // 확장자 기준 표시일 뿐이다 — 붙여넣기(`text`)와 구분되는 업로드 파일 형식임을
    // 화면이 TXT로 보여준다.
    expect(screen.getByText('안내문.txt')).toBeInTheDocument()
    expect(screen.getByText('TXT · 2KB')).toBeInTheDocument()

    await user.type(screen.getByLabelText('문서 제목'), '안내문')
    await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

    expect(vi.mocked(createDocumentFromFile)).toHaveBeenCalledWith(
      expect.objectContaining({ name: '안내문.txt' }),
      'w1',
      '안내문',
    )
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

    const belowCounter = screen.getByText(`${(threshold - 1).toLocaleString('ko-KR')} / 20,000자`)
    expect(belowCounter).toHaveClass('text-muted-foreground')
    expect(belowCounter).not.toHaveClass('text-warning')

    await user.paste('가')

    const atCounter = screen.getByText(`${threshold.toLocaleString('ko-KR')} / 20,000자`)
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
    // 좁히지 않은 조회가 한 번도 끼지 않았는지까지 본다 — 아래 「작업 공간이 정해지기
    // 전에는 부르지 않는다」와 같은 사실을 조회 인자 쪽에서 못박는다.
    for (const [params] of vi.mocked(listDocuments).mock.calls) {
      expect(params?.workspaceId).toBe('w2')
    }
  })

  it('작업 공간이 정해지기 전에는 문서 목록을 조회하지 않는다', async () => {
    renderPage({ workspaces: [], currentId: null })

    // 화면이 다 그려지고 effect가 돌 기회를 준 뒤에 본다.
    await screen.findByRole('button', { name: '쉬운 글 초안 만들기' })

    // 재는 것은 「제안이 안 보인다」가 아니라 **호출 자체가 없다**는 사실이다.
    // 작업 공간을 아직 모르는 채로 조회하면 요청에 workspace_id가 빠지고 서버는 기본
    // 범위로 답한다. 그 응답이 근거로 들어오면 화면은 "‘복지정책팀’ · 새 변환"이라고
    // 말하면서 다른 작업 공간의 문서 제목을 이어서 검수하라고 권하게 된다. 넓게 불러
    // 두고 화면에서만 거르는 방식으로 되돌리면 그 제목은 이미 브라우저에 와 있으므로,
    // 빈 화면을 재는 것으로는 회귀를 잡지 못한다.
    expect(vi.mocked(listDocuments)).not.toHaveBeenCalled()
    expect(screen.queryByRole('complementary', { name: '다음 할 일' })).not.toBeInTheDocument()
  })

  it('작업 공간이 정해지면 그때 그 작업 공간으로 좁혀 조회한다', async () => {
    vi.mocked(listDocuments).mockResolvedValue(
      documentPage([
        documentItem({
          id: 'd1',
          conversion_id: 'c1',
          status: 'done',
          reviewed_at: null,
          title: '민원 안내문',
        }),
      ]),
    )
    const { rerender } = render(page({ workspaces: [], currentId: null }))
    await screen.findByRole('button', { name: '쉬운 글 초안 만들기' })
    expect(vi.mocked(listDocuments)).not.toHaveBeenCalled()

    // 목록이 도착했다. 미룬 조회는 여기서 나가야 한다 — 안 부르는 것이 아니라 늦게
    // 부르는 것이 이 갈래의 값이다.
    rerender(
      page({ workspaces: [workspaceItem({ id: 'w2', name: '민원 안내' })], currentId: 'w2' }),
    )

    expect(await screen.findByRole('complementary', { name: '다음 할 일' })).toBeInTheDocument()
    expect(vi.mocked(listDocuments)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listDocuments)).toHaveBeenCalledWith(
      expect.objectContaining({ workspaceId: 'w2' }),
      expect.anything(),
    )
  })

  it('작업 공간을 바꾸면 이전 작업 공간의 제안이 화면에 남지 않는다', async () => {
    vi.mocked(listDocuments).mockResolvedValueOnce(
      documentPage([
        documentItem({
          id: 'd1',
          conversion_id: 'c1',
          status: 'done',
          reviewed_at: null,
          title: '복지정책팀 문서',
        }),
      ]),
    )
    const { rerender } = render(
      page({ workspaces: [workspaceItem({ id: 'w1', name: '복지정책팀' })], currentId: 'w1' }),
    )
    expect(await screen.findByText(/복지정책팀 문서/)).toBeInTheDocument()

    // 새 작업 공간의 응답은 아직 오지 않았다.
    vi.mocked(listDocuments).mockReturnValue(new Promise(() => undefined))
    rerender(
      page({ workspaces: [workspaceItem({ id: 'w2', name: '민원 안내' })], currentId: 'w2' }),
    )

    // 응답을 기다리는 동안 이전 공간의 제목이 남아 있으면, 화면은 민원 안내에서 작업
    // 중이라고 말하면서 복지정책팀 문서를 이어서 하라고 권하게 된다.
    expect(screen.queryByText(/복지정책팀 문서/)).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: '다음 할 일' })).not.toBeInTheDocument()
  })

  it('안내 카드는 개인정보 2종만 가린다고 알린다', () => {
    renderPage()

    const guide = screen.getByRole('region', { name: '이 작업에서 일어나는 일' })
    expect(within(guide).getByText('개인정보 2종 가림')).toBeInTheDocument()
    expect(within(guide).getByText(/주민등록번호와 카드번호/)).toBeInTheDocument()
    // 계약이 가리는 범주는 2종뿐이다 — 없는 보호를 약속하지 않는다.
    expect(within(guide).queryByText(/전화번호|이메일/)).not.toBeInTheDocument()
  })
  /**
   * §6.5 마지막 문단 — PDF 로 올린 결과를 PDF 로 다시 받을 수 없다는 사실은 **올리기 전에**
   * 알려야 한다. 겁주지 않는다: 업로드·변환·검수는 그대로 되고 못 하는 것은 내려받기 하나다.
   * 「준비 중」이라고 쓰지도 않는다 — 못 만든 기능이 아니라 하지 않기로 정해진 범위다.
   */
  it('올리기 전에 PDF 는 같은 형식으로 내려받지 않는다고 알린다', () => {
    renderPage()

    const guide = screen.getByRole('region', { name: '이 작업에서 일어나는 일' })
    expect(within(guide).getByText(/PDF는 출력용 형식이라/)).toBeInTheDocument()
    expect(within(guide).getByText(/업로드와 변환, 검수는 그대로 됩니다/)).toBeInTheDocument()
    expect(within(guide).queryByText(/준비 중/)).not.toBeInTheDocument()
  })

  /**
   * 이 안내는 정보이지 행동이 아니다 — 실행 버튼을 만들지 않고, 핵심 흐름의 주 행동
   * (`쉬운 글 초안 만들기`)이 있는 폼이 아니라 보조 안내 카드 안에 둔다(§2).
   */
  it('PDF 안내를 실행 버튼이나 대표 행동 옆에 두지 않는다', () => {
    renderPage()

    const guide = screen.getByRole('region', { name: '이 작업에서 일어나는 일' })
    expect(within(guide).queryByRole('button')).not.toBeInTheDocument()
    expect(within(guide).queryByRole('link')).not.toBeInTheDocument()
    const form = screen.getByRole('button', { name: '쉬운 글 초안 만들기' }).closest('form')
    expect(form).not.toBeNull()
    expect(within(form as HTMLElement).queryByText(/PDF는 출력용 형식이라/)).not.toBeInTheDocument()
  })

  it('고른 파일이 PDF 일 때만 파일 카드에서 같은 사실을 한 번 더 말한다', async () => {
    const user = userEvent.setup()
    renderPage()

    const input = await chooseFileMode(user)
    await user.upload(input, docxFile())

    const card = screen.getByRole('group', { name: /선택한 파일/ })
    expect(within(card).queryByText(/PDF는 출력용 형식이라/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '안내문.docx 파일 제거' }))
    await user.upload(await chooseFileMode(user), pdfFile())

    expect(
      within(screen.getByRole('group', { name: /선택한 파일/ })).getByText(/PDF는 출력용 형식이라/),
    ).toBeInTheDocument()
  })

  describe('이메일 인증', () => {
    it('이메일이 인증되지 않았으면 배너와 인증 화면 링크를 보여준다', () => {
      renderPage({}, { user: userResponse({ email_verified: false }) })

      expect(screen.getByText('이메일 인증 후 문서를 변환할 수 있습니다.')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '이메일 인증하기' })).toHaveAttribute(
        'href',
        '/verify-email',
      )
    })

    it('이메일이 인증되었으면 배너를 보여주지 않는다', () => {
      renderPage()

      expect(
        screen.queryByText('이메일 인증 후 문서를 변환할 수 있습니다.'),
      ).not.toBeInTheDocument()
    })

    it('업로드가 403(이메일 미인증)으로 거절되면 일반 오류 대신 배너를 보여준다', async () => {
      const user = userEvent.setup()
      vi.mocked(createDocumentFromText).mockRejectedValue(
        new ApiError(403, '이메일 인증 후 문서를 변환할 수 있습니다'),
      )
      renderPage()

      await user.type(screen.getByLabelText('문서 제목'), '청년 월세 지원 안내')
      await user.type(screen.getByLabelText('바꿀 글'), '신청 안내')
      await user.click(screen.getByRole('button', { name: '쉬운 글 초안 만들기' }))

      expect(
        await screen.findByText('이메일 인증 후 문서를 변환할 수 있습니다.'),
      ).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '이메일 인증하기' })).toBeInTheDocument()
      // 같은 사실을 일반 오류 문단으로 한 번 더 말하지 않는다.
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: '변환 화면' })).not.toBeInTheDocument()
    })
  })
})
