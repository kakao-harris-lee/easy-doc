import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  createIllustrationGenerationJob,
  downloadGeneratedIllustration,
  getGeneratedIllustrationImage,
  getGeneratedIllustrations,
  getIllustrationGenerationJob,
  listIllustrationGenerationJobs,
  updateGeneratedIllustrationApplication,
} from '../api/client'
import type {
  GeneratedIllustration,
  IllustrationGenerationJob,
  IllustrationSuggestion,
} from '../api/types'
import { IllustrationGenerationPanel } from './IllustrationGenerationPanel'

vi.mock('../api/client', async (original) => ({
  ...(await original<typeof import('../api/client')>()),
  createIllustrationGenerationJob: vi.fn(),
  downloadGeneratedIllustration: vi.fn(),
  getGeneratedIllustrationImage: vi.fn(),
  getGeneratedIllustrations: vi.fn(),
  getIllustrationGenerationJob: vi.fn(),
  listIllustrationGenerationJobs: vi.fn(),
  updateGeneratedIllustrationApplication: vi.fn(),
}))

const suggestion: IllustrationSuggestion = {
  suggestion_id: 's1',
  configuration_revision: 'a'.repeat(64),
  purpose: 'procedure',
  reason: '절차',
  body_range: { start: 0, end: 0 },
  source_anchors: [{ source_unit_indexes: [0], quote: '신청' }],
  scenes: ['신청'],
  preserved_facts: [],
  alt_text_draft: '신청 그림',
}
const job: IllustrationGenerationJob = {
  job_id: 'j1',
  request_id: 'req1',
  status: 'queued',
  suggestion_id: 's1',
  analysis_version: 'v1',
  configuration_revision: 'a'.repeat(64),
  based_on_content_revision: 1,
  reserved_credits: 1,
  failure_code: null,
  created_at: '2026-01-01',
  updated_at: '2026-01-01',
}
const asset: GeneratedIllustration = {
  asset_id: 'a1',
  job_id: 'j1',
  suggestion_id: 's1',
  analysis_version: 'v1',
  configuration_revision: 'a'.repeat(64),
  based_on_content_revision: 1,
  stale: false,
  applied: false,
  asset_revision: 1,
  purpose: 'procedure',
  reason: '절차',
  body_range: { start: 0, end: 0 },
  source_anchors: [{ source_unit_indexes: [0], quote: '신청' }],
  scenes: ['신청'],
  preserved_facts: [],
  alt_text: '신청 그림',
  provenance: 'fake',
  image_url: '/image',
  download_url: '/download',
  created_at: '2026-01-01',
}
const jobs = {
  enabled: true,
  active_job: null,
  latest_job: null,
  required_credits: 1,
  available_credits: 3,
}
const props = {
  conversionId: 'c1',
  contentRevision: 1,
  analysisVersion: 'v1',
  suggestions: [suggestion],
  disabled: false,
  removeDisabled: false,
}

beforeEach(() => {
  vi.mocked(listIllustrationGenerationJobs).mockReset().mockResolvedValue(jobs)
  vi.mocked(getGeneratedIllustrations)
    .mockReset()
    .mockResolvedValue({ content_revision: 1, assets: [] })
  vi.mocked(getGeneratedIllustrationImage)
    .mockReset()
    .mockResolvedValue(new Blob(['png']))
  vi.mocked(createIllustrationGenerationJob).mockReset()
  vi.mocked(getIllustrationGenerationJob).mockReset()
  vi.mocked(updateGeneratedIllustrationApplication).mockReset()
  vi.mocked(downloadGeneratedIllustration).mockReset()
  vi.stubGlobal('crypto', { randomUUID: () => 'req1' })
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:test'),
    revokeObjectURL: vi.fn(),
  })
})
afterEach(() => vi.unstubAllGlobals())

describe('그림 생성과 적용', () => {
  it('차감량 확인 후 한 번만 요청하고 기본 구성과 서버 revision을 전달한다', async () => {
    const user = userEvent.setup()
    vi.mocked(createIllustrationGenerationJob).mockResolvedValue({ job, creditBalance: 2 })
    render(<IllustrationGenerationPanel {...props} />)
    expect(await screen.findByText(/그림 한 장 필요 이용량: 1크레딧/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '그림 만들기' }))
    expect(createIllustrationGenerationJob).toHaveBeenCalledTimes(1)
    expect(createIllustrationGenerationJob).toHaveBeenCalledWith('c1', {
      request_id: 'req1',
      expected_content_revision: 1,
      suggestion_id: 's1',
      analysis_version: 'v1',
      configuration_revision: 'a'.repeat(64),
      configuration: { scenes: ['신청'], preserved_facts: [], alt_text: '신청 그림' },
    })
    expect(screen.getByRole('button', { name: '그림 만들기' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('그림을 만들고 있습니다')
  })

  it('재방문에서 비공개 PNG를 복구하고 확인 후 적용·제거하며 URL을 해제한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
    vi.mocked(updateGeneratedIllustrationApplication)
      .mockResolvedValueOnce({ ...asset, applied: true, asset_revision: 2 })
      .mockResolvedValueOnce({ ...asset, asset_revision: 3 })
    const view = render(<IllustrationGenerationPanel {...props} />)
    expect(await screen.findByRole('img', { name: '신청 그림' })).toHaveAttribute(
      'src',
      'blob:test',
    )
    expect(screen.getByText(/테스트 그림입니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '문서에 그림 적용' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '문서에 그림 적용' }))
    expect(updateGeneratedIllustrationApplication).toHaveBeenCalledWith('c1', 'a1', {
      expected_content_revision: 1,
      expected_asset_revision: 1,
      applied: true,
    })
    await user.click(await screen.findByRole('button', { name: '문서에서 그림 제거' }))
    expect(updateGeneratedIllustrationApplication).toHaveBeenLastCalledWith('c1', 'a1', {
      expected_content_revision: 1,
      expected_asset_revision: 2,
      applied: false,
    })
    view.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test')
  })

  it('실제 provider 결과에 테스트 표기를 붙이지 않고 stale 적용을 막는다', async () => {
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({
      content_revision: 1,
      assets: [{ ...asset, provenance: 'provider', stale: true }],
    })
    render(<IllustrationGenerationPanel {...props} />)
    expect(await screen.findByRole('img')).toBeInTheDocument()
    expect(screen.queryByText(/테스트 그림입니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/이전 버전 본문/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '문서에 그림 적용' })).toBeDisabled()
  })

  it('요청 응답이 불명확하면 같은 요청 ID만 재전송한다', async () => {
    const user = userEvent.setup()
    vi.mocked(createIllustrationGenerationJob)
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ job, creditBalance: 2 })
    render(<IllustrationGenerationPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '그림 만들기' }))
    expect(await screen.findByText(/요청 결과를 확인하지 못했습니다/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '같은 그림 요청 다시 보내기' }))
    expect(vi.mocked(createIllustrationGenerationJob).mock.calls[1]?.[1]).toEqual(
      vi.mocked(createIllustrationGenerationJob).mock.calls[0]?.[1],
    )
  })

  it('기능이 꺼졌으면 자산을 읽지 않고 생성도 제공하지 않는다', async () => {
    vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({ ...jobs, enabled: false })
    render(<IllustrationGenerationPanel {...props} />)
    expect(await screen.findByText('현재 그림 생성을 사용할 수 없습니다.')).toBeInTheDocument()
    expect(getGeneratedIllustrations).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '그림 만들기' })).not.toBeInTheDocument()
  })

  it('진행 중 작업을 복구해 완료까지 폴링한다', async () => {
    vi.mocked(listIllustrationGenerationJobs)
      .mockResolvedValueOnce({ ...jobs, active_job: job, latest_job: job })
      .mockResolvedValue({ ...jobs, latest_job: { ...job, status: 'succeeded' } })
    vi.mocked(getIllustrationGenerationJob).mockResolvedValue({ ...job, status: 'succeeded' })
    vi.mocked(getGeneratedIllustrations)
      .mockResolvedValueOnce({ content_revision: 1, assets: [] })
      .mockResolvedValue({ content_revision: 1, assets: [asset] })
    render(<IllustrationGenerationPanel {...props} />)
    await screen.findByText(/그림을 만들고 있습니다/)
    await waitFor(
      () =>
        expect(getIllustrationGenerationJob).toHaveBeenCalledWith(
          'c1',
          'j1',
          expect.any(AbortSignal),
        ),
      { timeout: 4000 },
    )
    expect(screen.getByRole('status')).toHaveTextContent('그림 생성이 완료되었습니다')
    expect(await screen.findByRole('img', { name: '신청 그림' })).toBeInTheDocument()
  })
  it('stale 상태에서 적용은 막고 이미 적용된 그림은 현재 본문 revision으로 제거한다', async () => {
    const user = userEvent.setup()
    const staleAsset = { ...asset, stale: true, applied: true, asset_revision: 4 }
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({
      content_revision: 2,
      assets: [staleAsset],
    })
    vi.mocked(updateGeneratedIllustrationApplication).mockResolvedValue({
      ...staleAsset,
      applied: false,
      asset_revision: 5,
    })
    render(
      <IllustrationGenerationPanel
        {...props}
        contentRevision={2}
        disabled
        removeDisabled={false}
      />,
    )
    await user.click(await screen.findByRole('button', { name: '문서에서 그림 제거' }))
    expect(updateGeneratedIllustrationApplication).toHaveBeenCalledWith('c1', 'a1', {
      expected_content_revision: 2,
      expected_asset_revision: 4,
      applied: false,
    })
    expect(await screen.findByRole('button', { name: '문서에 그림 적용' })).toBeDisabled()
  })

  it('완료된 멱등 재전송 응답은 자산을 다시 읽어 미리보기를 표시한다', async () => {
    const user = userEvent.setup()
    const completed = { ...job, status: 'succeeded' as const }
    vi.mocked(createIllustrationGenerationJob)
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ job: completed, creditBalance: 2 })
    vi.mocked(listIllustrationGenerationJobs)
      .mockResolvedValueOnce(jobs)
      .mockResolvedValue({ ...jobs, latest_job: completed })
    vi.mocked(getGeneratedIllustrations)
      .mockResolvedValueOnce({ content_revision: 1, assets: [] })
      .mockResolvedValue({ content_revision: 1, assets: [asset] })
    render(<IllustrationGenerationPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '그림 만들기' }))
    await user.click(await screen.findByRole('button', { name: '같은 그림 요청 다시 보내기' }))
    expect(await screen.findByRole('img', { name: '신청 그림' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('그림 생성이 완료되었습니다')
  })

  it('오래 걸린 새로고침 응답이 이후 적용 결과를 덮어쓰지 않는다', async () => {
    const user = userEvent.setup()
    let resolveRefresh!: (value: typeof jobs) => void
    const refresh = new Promise<typeof jobs>((resolve) => {
      resolveRefresh = resolve
    })
    vi.mocked(listIllustrationGenerationJobs)
      .mockResolvedValueOnce(jobs)
      .mockReturnValueOnce(refresh)
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
    vi.mocked(updateGeneratedIllustrationApplication).mockResolvedValue({
      ...asset,
      applied: true,
      asset_revision: 2,
    })
    render(<IllustrationGenerationPanel {...props} />)
    await screen.findByRole('img')
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '그림 상태 새로고침' }))
    await user.click(screen.getByRole('button', { name: '문서에 그림 적용' }))
    expect(await screen.findByRole('button', { name: '문서에서 그림 제거' })).toBeEnabled()
    await act(async () => {
      resolveRefresh(jobs)
      await refresh
    })
    expect(screen.getByRole('button', { name: '문서에서 그림 제거' })).toBeEnabled()
  })

  it('이미지 조회가 실패해도 미리보기를 다시 불러올 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
    vi.mocked(getGeneratedIllustrationImage)
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(new Blob(['png']))
    render(<IllustrationGenerationPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '미리보기 다시 불러오기' }))
    expect(await screen.findByRole('img', { name: '신청 그림' })).toBeInTheDocument()
    expect(getGeneratedIllustrationImage).toHaveBeenCalledTimes(2)
  })
  it('문서별 생성 한도 거절을 충돌과 구별해 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(createIllustrationGenerationJob).mockRejectedValue(
      new ApiError(409, '문서당 그림 생성 요청 한도에 도달했습니다'),
    )
    render(<IllustrationGenerationPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '그림 만들기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('최대 10건')
    expect(screen.getByRole('alert')).toHaveTextContent('결과를 확인하지 못한 요청도 포함')
  })
  it('완료된 작업의 조회 뒤 다른 탭의 새 활성 작업이 있으면 새 작업을 계속 폴링한다', async () => {
    const nextJob = { ...job, job_id: 'j2', request_id: 'req2' }
    vi.mocked(listIllustrationGenerationJobs)
      .mockResolvedValueOnce({ ...jobs, active_job: job, latest_job: job })
      .mockResolvedValue({ ...jobs, active_job: nextJob, latest_job: nextJob })
    vi.mocked(getIllustrationGenerationJob).mockImplementation(async (_conversion, jobId) =>
      jobId === 'j1' ? { ...job, status: 'succeeded' } : { ...nextJob, status: 'running' },
    )
    render(<IllustrationGenerationPanel {...props} />)
    await screen.findByText(/그림을 만들고 있습니다/)
    await waitFor(
      () =>
        expect(getIllustrationGenerationJob).toHaveBeenCalledWith(
          'c1',
          'j2',
          expect.any(AbortSignal),
        ),
      { timeout: 6500 },
    )
    expect(screen.getByRole('button', { name: '그림 만들기' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('그림을 만들고 있습니다')
  }, 8000)
})
