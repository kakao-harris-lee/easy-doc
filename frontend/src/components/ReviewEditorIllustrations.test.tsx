import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  getGeneratedIllustrations,
  getIllustrationSuggestions,
  listIllustrationGenerationJobs,
  updateGeneratedIllustrationApplication,
} from '../api/client'
import type { GeneratedIllustration } from '../api/types'
import { conversion, sourceReady } from '../test/factories'
import { setUnsavedChanges } from '../review/unsavedChanges'
import { ReviewEditor } from './ReviewEditor'

vi.mock('../api/client', async (original) => ({
  ...(await original<typeof import('../api/client')>()),
  getIllustrationSuggestions: vi.fn(async () => ({
    status: 'no_suggestions',
    analysis_version: 'v1',
    content_revision: 1,
    based_on_content_revision: 1,
    required_credits: 0,
    suggestions: [],
    dropped_count: 0,
  })),
  listIllustrationSuggestionJobs: vi.fn(async () => ({
    active_job: null,
    latest_job: null,
    required_credits: 0,
    available_credits: 3,
  })),
  listIllustrationGenerationJobs: vi.fn(async () => ({
    enabled: true,
    active_job: null,
    latest_job: null,
    required_credits: 0,
    available_credits: 3,
    request_count: 1,
    request_limit: 10,
    remaining_request_count: 9,
  })),
  getGeneratedIllustrations: vi.fn(),
  getGeneratedIllustrationImage: vi.fn(async () => new Blob(['png'])),
  updateGeneratedIllustrationApplication: vi.fn(),
}))

const asset: GeneratedIllustration = {
  asset_id: 'a1',
  job_id: 'j1',
  suggestion_id: 's1',
  analysis_version: 'v1',
  configuration_revision: 'a'.repeat(64),
  based_on_content_revision: 1,
  stale: false,
  applied: false,
  discarded: false,
  asset_revision: 1,
  purpose: 'procedure',
  reason: '절차',
  body_range: { start: 0, end: 1 },
  source_anchors: [{ source_unit_indexes: [0], quote: '신청' }],
  scenes: ['신청'],
  preserved_facts: [],
  alt_text: '신청하는 사람',
  provenance: 'fake',
  image_url: '/image',
  download_url: '/download',
  created_at: '2026-01-01',
}
const document = conversion({
  content_revision: 1,
  easy_text: '먼저 신청하세요.\n서류를 내세요.\n결과를 기다리세요.',
  review_capabilities: {
    review_support: false,
    focused_review: false,
    action_guide: false,
    table_relations: false,
    review_history: false,
    explanations: false,
    illustrations: false,
    illustration_suggestions: true,
    illustration_generation: true,
  },
})
function editor(id = document.id) {
  return (
    <MemoryRouter>
      <ReviewEditor conversion={{ ...document, id }} source={sourceReady('신청')} />
    </MemoryRouter>
  )
}
beforeEach(() => {
  vi.mocked(getIllustrationSuggestions).mockClear()
  vi.mocked(getGeneratedIllustrations)
    .mockReset()
    .mockResolvedValue({ content_revision: 1, assets: [asset] })
  vi.mocked(updateGeneratedIllustrationApplication).mockReset()
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:test'),
    revokeObjectURL: vi.fn(),
  })
})
afterEach(() => {
  vi.unstubAllGlobals()
  setUnsavedChanges(false)
})

it('검토 후 적용한 그림을 문맥 끝에 표시하고 제거하면 문서 미리보기에서도 없앤다', async () => {
  const user = userEvent.setup()
  vi.mocked(updateGeneratedIllustrationApplication)
    .mockResolvedValueOnce({ ...asset, applied: true, asset_revision: 2 })
    .mockResolvedValueOnce({ ...asset, asset_revision: 3 })
  render(editor())
  await user.click(screen.getByRole('button', { name: '그림 제안 보기 (선택)' }))
  const panel = await screen.findByRole('region', { name: '그림 생성과 적용' })
  await within(panel).findByRole('img')
  expect(
    screen.queryByRole('region', { name: '그림을 적용한 문서 미리보기' }),
  ).not.toBeInTheDocument()
  await user.click(within(panel).getByRole('checkbox'))
  await user.click(within(panel).getByRole('button', { name: '문서에 그림 적용' }))
  const preview = await screen.findByRole('region', { name: '그림을 적용한 문서 미리보기' })
  await waitFor(() => expect(preview.querySelector('img')).toHaveAttribute('src', 'blob:test'))
  const image = preview.querySelector('img')!
  expect(image).toHaveAttribute('alt', '')
  expect(within(preview).getByText('신청하는 사람').tagName).toBe('FIGCAPTION')
  expect(image.closest('figure')?.previousElementSibling).toHaveTextContent('서류를 내세요.')
  expect(image.closest('figure')?.nextElementSibling).toHaveTextContent('결과를 기다리세요.')
  expect(within(preview).getByText(/테스트 그림입니다/)).toBeVisible()
  expect(screen.getByText(/적용한 생성 그림은 DOCX/)).toBeVisible()
  await user.click(within(panel).getByRole('button', { name: '문서에서 그림 제거' }))
  await waitFor(() =>
    expect(
      screen.queryByRole('region', { name: '그림을 적용한 문서 미리보기' }),
    ).not.toBeInTheDocument(),
  )
  expect(screen.queryByText(/적용한 생성 그림은 DOCX/)).not.toBeInTheDocument()
})

it('서버의 적용 그림을 접힌 패널에서도 복구하고 편집 중 또는 문서 전환 시 숨긴다', async () => {
  vi.mocked(getGeneratedIllustrations).mockResolvedValue({
    content_revision: 1,
    assets: [{ ...asset, applied: true }],
  })
  const view = render(editor())
  const preview = await screen.findByRole('region', { name: '그림을 적용한 문서 미리보기' })
  await waitFor(() => expect(preview.querySelector('img')).toHaveAttribute('src', 'blob:test'))
  const input = screen.getByLabelText('쉬운 글 결과 (고칠 수 있습니다)')
  fireEvent.change(input, { target: { value: '수정 중' } })
  expect(
    screen.queryByRole('region', { name: '그림을 적용한 문서 미리보기' }),
  ).not.toBeInTheDocument()
  fireEvent.change(input, { target: { value: document.easy_text } })
  expect(await screen.findByRole('region', { name: '그림을 적용한 문서 미리보기' })).toBeVisible()
  vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [] })
  view.rerender(editor('other-document'))
  expect(
    screen.queryByRole('region', { name: '그림을 적용한 문서 미리보기' }),
  ).not.toBeInTheDocument()
})

it.each([
  { stale: true },
  { based_on_content_revision: 2 },
  { body_range: { start: 0, end: 99 } },
  { source_anchors: [] },
])('현재 본문에 근거를 붙일 수 없는 그림을 숨긴다: %j', async (change) => {
  vi.mocked(getGeneratedIllustrations).mockResolvedValue({
    content_revision: 1,
    assets: [{ ...asset, applied: true, ...change }],
  })
  render(editor())
  await screen.findByText('그림 1 미리보기 · 적용됨')
  expect(
    screen.queryByRole('region', { name: '그림을 적용한 문서 미리보기' }),
  ).not.toBeInTheDocument()
})

it('제안 read가 꺼져도 생성 read로 기존 자산과 적용 미리보기를 독립 복구한다', async () => {
  const user = userEvent.setup()
  const capabilities = {
    ...document.review_capabilities!,
    illustration_suggestions: true,
    illustration_suggestions_read: false,
    illustration_suggestions_create: false,
    illustration_generation: false,
    illustration_generation_read: true,
    illustration_generation_create: false,
  }
  vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
    enabled: false,
    active_job: null,
    latest_job: null,
    required_credits: null,
    available_credits: 3,
    request_count: 1,
    request_limit: 10,
    remaining_request_count: 9,
  })
  vi.mocked(getGeneratedIllustrations).mockResolvedValue({
    content_revision: 1,
    assets: [{ ...asset, applied: true }],
  })
  render(
    <MemoryRouter>
      <ReviewEditor
        conversion={{ ...document, review_capabilities: capabilities }}
        source={sourceReady('신청')}
      />
    </MemoryRouter>,
  )
  expect(
    await screen.findByRole('region', { name: '그림을 적용한 문서 미리보기' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '생성 그림 보기 (선택)' }))
  const panel = await screen.findByRole('region', { name: '그림 생성과 적용' })
  expect(await within(panel).findByRole('img', { name: '신청하는 사람' })).toBeInTheDocument()
  expect(within(panel).getByText(/기존 작업과 그림은 계속 확인할 수 있습니다/)).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '그림 제안' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '그림 만들기' })).not.toBeInTheDocument()
  expect(getIllustrationSuggestions).not.toHaveBeenCalled()
})

it('생성 read가 꺼지면 자산 조회와 적용 미리보기를 만들지 않는다', async () => {
  const capabilities = {
    ...document.review_capabilities!,
    illustration_suggestions_read: false,
    illustration_generation_read: false,
  }
  render(
    <MemoryRouter>
      <ReviewEditor
        conversion={{ ...document, review_capabilities: capabilities }}
        source={sourceReady('신청')}
      />
    </MemoryRouter>,
  )
  expect(screen.queryByRole('button', { name: /그림.*선택/ })).not.toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '그림을 적용한 문서 미리보기' }),
  ).not.toBeInTheDocument()
  expect(getGeneratedIllustrations).not.toHaveBeenCalled()
})
