import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
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
  updateGeneratedIllustrationReview,
} from '../api/client'
import type {
  GeneratedIllustration,
  IllustrationGenerationJob,
  IllustrationGenerationJobCollection,
  IllustrationSuggestion,
} from '../api/types'
import { illustrationConfigurationRevision } from '../api/illustrationConfiguration'
import { IllustrationGenerationPanel } from './IllustrationGenerationPanel'

vi.mock('../api/illustrationConfiguration', async (original) => ({
  ...(await original<typeof import('../api/illustrationConfiguration')>()),
  illustrationConfigurationRevision: vi.fn(),
}))

vi.mock('../api/client', async (original) => ({
  ...(await original<typeof import('../api/client')>()),
  createIllustrationGenerationJob: vi.fn(),
  downloadGeneratedIllustration: vi.fn(),
  getGeneratedIllustrationImage: vi.fn(),
  getGeneratedIllustrations: vi.fn(),
  getIllustrationGenerationJob: vi.fn(),
  listIllustrationGenerationJobs: vi.fn(),
  updateGeneratedIllustrationApplication: vi.fn(),
  updateGeneratedIllustrationReview: vi.fn(),
}))

const reviewGuidance =
  '적용하기 전에 그림의 화살표·갈림길·막대 비례가 위 장면·보존할 사실과 맞는지 확인하세요. 맞지 않으면 적용하지 말고 이 후보를 폐기하세요.'
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
  discarded: false,
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
const jobs: IllustrationGenerationJobCollection = {
  enabled: true,
  active_job: null,
  latest_job: null,
  required_credits: 1,
  available_credits: 3,
  request_count: 0,
  request_limit: 10,
  remaining_request_count: 10,
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
  vi.mocked(illustrationConfigurationRevision).mockReset().mockResolvedValue('b'.repeat(64))
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
  vi.mocked(updateGeneratedIllustrationReview).mockReset()
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
    expect(createIllustrationGenerationJob).toHaveBeenCalledWith(
      'c1',
      {
        request_id: 'req1',
        expected_content_revision: 1,
        suggestion_id: 's1',
        analysis_version: 'v1',
        configuration_revision: 'a'.repeat(64),
        configuration: { scenes: ['신청'], preserved_facts: [], alt_text: '신청 그림' },
      },
      expect.any(AbortSignal),
    )
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
    expect(updateGeneratedIllustrationApplication).toHaveBeenCalledWith(
      'c1',
      'a1',
      { expected_content_revision: 1, expected_asset_revision: 1, applied: true },
      expect.any(AbortSignal),
    )
    await user.click(await screen.findByRole('button', { name: '문서에서 그림 제거' }))
    expect(updateGeneratedIllustrationApplication).toHaveBeenLastCalledWith(
      'c1',
      'a1',
      { expected_content_revision: 1, expected_asset_revision: 2, applied: false },
      expect.any(AbortSignal),
    )
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
    expect(screen.queryByText(/화살표·갈림길·막대 비례/)).not.toBeInTheDocument()
  })

  it('적용 전 그림에는 도형 대조 안내를 두고 확인 체크박스의 설명으로 연결한다', async () => {
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
    render(<IllustrationGenerationPanel {...props} />)
    await screen.findByRole('img', { name: '신청 그림' })
    expect(screen.getByText(reviewGuidance)).toBeInTheDocument()
    expect(
      screen.getByRole('checkbox', {
        name: '장면·보존할 사실과 그림(화살표·갈림길·비례), 대체텍스트가 맞는지 확인했습니다.',
      }),
    ).toHaveAccessibleDescription(reviewGuidance)
  })

  it('이미 적용된 그림에는 도형 대조 안내를 두지 않는다', async () => {
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({
      content_revision: 1,
      assets: [{ ...asset, applied: true }],
    })
    render(<IllustrationGenerationPanel {...props} />)
    await screen.findByRole('img', { name: '신청 그림' })
    expect(screen.queryByText(reviewGuidance)).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it.each([0, 502, 504])('요청 응답이 불명확하면 같은 요청 ID만 재전송한다: %s', async (status) => {
    const user = userEvent.setup()
    const uuid = vi.spyOn(crypto, 'randomUUID')
    vi.mocked(createIllustrationGenerationJob)
      .mockRejectedValueOnce(new ApiError(status, 'network'))
      .mockResolvedValueOnce({ job, creditBalance: 2 })
    render(<IllustrationGenerationPanel {...props} />)
    await user.click(await screen.findByRole('button', { name: '그림 만들기' }))
    expect(await screen.findByText(/요청 결과를 확인하지 못했습니다/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '같은 그림 요청 재전송' }))
    expect(vi.mocked(createIllustrationGenerationJob).mock.calls[1]?.[1]).toEqual(
      vi.mocked(createIllustrationGenerationJob).mock.calls[0]?.[1],
    )
    expect(uuid).toHaveBeenCalledTimes(1)
  })

  it('신규 생성이 꺼져도 기존 자산을 읽고 접수만 제공하지 않는다', async () => {
    vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({ ...jobs, enabled: false })
    vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
    render(<IllustrationGenerationPanel {...props} />)
    expect(await screen.findByRole('img', { name: '신청 그림' })).toBeInTheDocument()
    expect(screen.getByText(/기존 작업과 그림은 계속 확인할 수 있습니다/)).toBeInTheDocument()
    expect(getGeneratedIllustrations).toHaveBeenCalledWith('c1', expect.any(AbortSignal))
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
    expect(updateGeneratedIllustrationApplication).toHaveBeenCalledWith(
      'c1',
      'a1',
      { expected_content_revision: 2, expected_asset_revision: 4, applied: false },
      expect.any(AbortSignal),
    )
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
    await user.click(await screen.findByRole('button', { name: '같은 그림 요청 재전송' }))
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
    expect(screen.getByRole('alert')).toHaveTextContent(/결과를 확인하지 못한 요청.*재생성도 포함/)
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

it('수정한 대체텍스트의 구성 지문을 보내고 불명확한 요청을 그대로 재전송한다', async () => {
  const user = userEvent.setup()
  vi.mocked(createIllustrationGenerationJob)
    .mockRejectedValueOnce(new ApiError(0, 'network'))
    .mockResolvedValueOnce({ job, creditBalance: 2 })
  render(<IllustrationGenerationPanel {...props} />)
  const input = await screen.findByLabelText('그림 대체텍스트')
  await user.clear(input)
  expect(screen.getByRole('button', { name: '그림 만들기' })).toBeDisabled()
  await user.type(input, '신청하는 사람')
  await user.click(screen.getByRole('button', { name: '그림 만들기' }))
  expect(illustrationConfigurationRevision).toHaveBeenCalledWith({
    scenes: ['신청'],
    preserved_facts: [],
    alt_text: '신청하는 사람',
  })
  expect(createIllustrationGenerationJob).toHaveBeenCalledWith(
    'c1',
    expect.objectContaining({
      configuration_revision: 'b'.repeat(64),
      configuration: expect.objectContaining({ alt_text: '신청하는 사람' }),
    }),
    expect.any(AbortSignal),
  )
  expect(input).toBeDisabled()
  await user.click(await screen.findByRole('button', { name: '같은 그림 요청 재전송' }))
  expect(vi.mocked(createIllustrationGenerationJob).mock.calls[1]?.[1]).toEqual(
    vi.mocked(createIllustrationGenerationJob).mock.calls[0]?.[1],
  )
})

it('구성 지문 계산 중 본문이 수정되면 생성을 보내지 않는다', async () => {
  const user = userEvent.setup()
  let resolve!: (value: string) => void
  vi.mocked(illustrationConfigurationRevision).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const view = render(<IllustrationGenerationPanel {...props} />)
  await user.type(await screen.findByLabelText('그림 대체텍스트'), ' 수정')
  await user.click(screen.getByRole('button', { name: '그림 만들기' }))
  view.rerender(<IllustrationGenerationPanel {...props} disabled />)
  await act(async () => resolve('b'.repeat(64)))
  expect(createIllustrationGenerationJob).not.toHaveBeenCalled()
})

it('대체텍스트와 남은 글자 수는 Unicode 코드 포인트 300자 한도를 따른다', async () => {
  render(<IllustrationGenerationPanel {...props} />)
  const input = await screen.findByLabelText('그림 대체텍스트')
  expect(input).not.toHaveAttribute('maxlength')
  fireEvent.change(input, { target: { value: '😀'.repeat(300) } })
  expect(screen.getByRole('button', { name: '그림 만들기' })).toBeEnabled()
  expect(screen.getByText('현재 300자 · 남은 0자')).toBeInTheDocument()
  fireEvent.change(input, { target: { value: '😀'.repeat(301) } })
  expect(screen.getByRole('button', { name: '그림 만들기' })).toBeDisabled()
  expect(input).toHaveAccessibleDescription(/1자 초과/)
})

it('문서 전환 중 늦은 접수 응답이 새 문서의 이용량과 상태를 덮지 않고 재방문 시 복구한다', async () => {
  const user = userEvent.setup()
  let resolveA!: (value: { job: IllustrationGenerationJob; creditBalance: number }) => void
  let signalA: AbortSignal | undefined
  vi.mocked(createIllustrationGenerationJob).mockImplementation((_id, _body, signal) => {
    signalA = signal
    return new Promise((resolve) => {
      resolveA = resolve
    })
  })
  vi.mocked(listIllustrationGenerationJobs).mockImplementation(async (id) =>
    id === 'c2' ? { ...jobs, available_credits: 9 } : jobs,
  )
  const view = render(<IllustrationGenerationPanel {...props} />)
  await user.click(await screen.findByRole('button', { name: '그림 만들기' }))
  view.rerender(<IllustrationGenerationPanel {...props} conversionId="c2" />)
  expect(signalA?.aborted).toBe(true)
  expect(await screen.findByText(/남은 이용량 9크레딧/)).toBeInTheDocument()
  await act(async () => {
    resolveA({ job, creditBalance: 1 })
    await Promise.resolve()
  })
  expect(screen.getByText(/남은 이용량 9크레딧/)).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(createIllustrationGenerationJob).toHaveBeenCalledTimes(1)

  vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
    ...jobs,
    active_job: job,
    latest_job: job,
  })
  view.rerender(<IllustrationGenerationPanel {...props} />)
  expect(await screen.findByText(/그림을 만들고 있습니다/)).toBeInTheDocument()
  expect(createIllustrationGenerationJob).toHaveBeenCalledTimes(1)
})

it.each([401, 404])('%s 폴링 오류는 즉시 중단한다', async (status) => {
  vi.useFakeTimers()
  try {
    vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
      ...jobs,
      active_job: job,
      latest_job: job,
    })
    vi.mocked(getIllustrationGenerationJob).mockRejectedValue(new ApiError(status, '영구 오류'))
    render(<IllustrationGenerationPanel {...props} />)
    await act(async () => await Promise.resolve())
    await act(async () => await vi.advanceTimersByTimeAsync(3000))
    expect(getIllustrationGenerationJob).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('alert')).toHaveTextContent(
      status === 401 ? /다시 로그인/ : /문서 상태를 확인/,
    )
    await act(async () => await vi.advanceTimersByTimeAsync(20_000))
    expect(getIllustrationGenerationJob).toHaveBeenCalledTimes(1)
  } finally {
    vi.useRealTimers()
  }
})

it('일시 폴링 오류는 세 번 뒤 중단하고 수동 새로고침으로 재개한다', async () => {
  vi.useFakeTimers()
  try {
    vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
      ...jobs,
      active_job: job,
      latest_job: job,
    })
    vi.mocked(getIllustrationGenerationJob).mockRejectedValue(new ApiError(503, '일시 오류'))
    render(<IllustrationGenerationPanel {...props} />)
    await act(async () => await Promise.resolve())
    await act(async () => await vi.advanceTimersByTimeAsync(3000))
    await act(async () => await vi.advanceTimersByTimeAsync(5000))
    await act(async () => await vi.advanceTimersByTimeAsync(10_000))
    expect(getIllustrationGenerationJob).toHaveBeenCalledTimes(3)
    expect(screen.getByRole('alert')).toHaveTextContent(/서버 작업 취소나 이용량 반환을 뜻하지는/)
    fireEvent.click(screen.getByRole('button', { name: '그림 상태 새로고침' }))
    await act(async () => await Promise.resolve())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await act(async () => await vi.advanceTimersByTimeAsync(3000))
    expect(getIllustrationGenerationJob).toHaveBeenCalledTimes(4)
  } finally {
    vi.useRealTimers()
  }
})

it.each([
  ['목록', 404],
  ['자산', 404],
  ['자산', 503],
] as const)('terminal 상태는 %s 조회의 %s 오류와 무관하게 유지한다', async (refresh, status) => {
  vi.useFakeTimers()
  try {
    vi.mocked(listIllustrationGenerationJobs).mockResolvedValueOnce({
      ...jobs,
      active_job: job,
      latest_job: job,
    })
    if (refresh === '목록') {
      vi.mocked(listIllustrationGenerationJobs).mockRejectedValue(new ApiError(status, '조회 실패'))
    } else {
      vi.mocked(listIllustrationGenerationJobs).mockResolvedValue(jobs)
      vi.mocked(getGeneratedIllustrations)
        .mockResolvedValueOnce({ content_revision: 1, assets: [] })
        .mockRejectedValue(new ApiError(status, '조회 실패'))
    }
    vi.mocked(getIllustrationGenerationJob).mockResolvedValue({
      ...job,
      status: 'failed',
      failure_code: 'generation_failed',
    })
    render(<IllustrationGenerationPanel {...props} />)
    await act(async () => await Promise.resolve())
    await act(async () => await vi.advanceTimersByTimeAsync(3000))

    expect(screen.getByRole('status')).toHaveTextContent(/이미지 제공 서비스/)
    expect(screen.getByRole('status')).toHaveTextContent(/예약 이용량 반환이 확인됐습니다/)
    expect(screen.queryByText(/그림을 만들고 있습니다/)).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await act(async () => await vi.advanceTimersByTimeAsync(20_000))
    expect(getIllustrationGenerationJob).toHaveBeenCalledTimes(1)
  } finally {
    vi.useRealTimers()
  }
})

it('402는 필요량·가용량과 기존 이용량·충전 이동을 제공한다', async () => {
  const user = userEvent.setup()
  vi.mocked(createIllustrationGenerationJob).mockRejectedValue(
    new ApiError(402, '부족', null, null, 0.5, 2),
  )
  render(<IllustrationGenerationPanel {...props} />)
  await user.click(await screen.findByRole('button', { name: '그림 만들기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(/필요 이용량 2크레딧/)
  expect(screen.getByRole('alert')).toHaveTextContent(/사용 가능 0.5크레딧/)
  expect(screen.getByRole('link', { name: '기존 이용량 확인·충전으로 이동' })).toHaveAttribute(
    'href',
    '/usage',
  )
})

it.each([
  ['generation_failed', /이미지 제공 서비스/],
  ['result_invalid', /이미지 형식이나 내용 검사/],
  ['outcome_unknown', /최종 결과를 확인하지 못했습니다/],
] as const)('%s 실패의 원인·반환·다음 행동을 구분한다', async (failureCode, pattern) => {
  vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
    ...jobs,
    latest_job: { ...job, status: 'failed', failure_code: failureCode },
  })
  render(<IllustrationGenerationPanel {...props} />)
  expect(await screen.findByText(pattern)).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent(/예약 이용량 반환이 확인됐습니다/)
  expect(screen.getByRole('status')).toHaveTextContent(/새 요청으로만 재생성/)
  expect(createIllustrationGenerationJob).not.toHaveBeenCalled()
})

it('superseded는 본문 변경과 반환·최신 제안 확인을 안내한다', async () => {
  vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
    ...jobs,
    latest_job: { ...job, status: 'superseded' },
  })
  render(<IllustrationGenerationPanel {...props} />)
  expect(await screen.findByText(/본문이 바뀌어/)).toHaveTextContent(/예약 이용량 반환/)
  expect(screen.getByRole('status')).toHaveTextContent(/최신 제안을 확인/)
})

it('생성 전 10건 한도와 재생성 포함 규칙을 표시하고 잔여 0건은 막는다', async () => {
  const view = render(<IllustrationGenerationPanel {...props} />)
  expect(await screen.findByText(/생성 요청 0\/10건 · 남은 요청 10건/)).toHaveTextContent(
    /재생성도 포함됩니다/,
  )
  vi.mocked(listIllustrationGenerationJobs).mockResolvedValue({
    ...jobs,
    request_count: 10,
    remaining_request_count: 0,
  })
  view.rerender(<IllustrationGenerationPanel {...props} contentRevision={2} />)
  expect(await screen.findByText(/생성 요청 10\/10건 · 남은 요청 0건/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '그림 만들기' })).toBeDisabled()
})

it('대체텍스트를 CAS로 저장하고 생성 구성 provenance를 유지한다', async () => {
  const user = userEvent.setup()
  vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
  vi.mocked(updateGeneratedIllustrationReview).mockResolvedValue({
    ...asset,
    alt_text: '수정한 그림 설명',
    asset_revision: 2,
  })
  render(<IllustrationGenerationPanel {...props} />)
  await user.click(await screen.findByRole('button', { name: '대체텍스트 수정' }))
  const input = screen.getByLabelText('그림 1 대체텍스트 수정')
  await user.clear(input)
  await user.type(input, '수정한 그림 설명')
  await user.click(screen.getByRole('button', { name: '대체텍스트 저장' }))
  expect(updateGeneratedIllustrationReview).toHaveBeenCalledWith(
    'c1',
    'a1',
    { expected_content_revision: 1, expected_asset_revision: 1, alt_text: '수정한 그림 설명' },
    expect.any(AbortSignal),
  )
  expect(await screen.findByText('대체텍스트: 수정한 그림 설명')).toBeInTheDocument()
  expect(screen.getByText(`생성 구성 기록: ${'a'.repeat(64)}`)).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent(/생성 당시 구성 기록은 바뀌지 않습니다/)
})

it('미적용 후보를 soft 폐기하고 목록에서 제외하며 이용량 불반환을 알린다', async () => {
  const user = userEvent.setup()
  vi.stubGlobal(
    'confirm',
    vi.fn(() => true),
  )
  vi.mocked(getGeneratedIllustrations).mockResolvedValue({ content_revision: 1, assets: [asset] })
  vi.mocked(updateGeneratedIllustrationReview).mockResolvedValue({
    ...asset,
    discarded: true,
    asset_revision: 2,
  })
  render(<IllustrationGenerationPanel {...props} />)
  await user.click(await screen.findByRole('button', { name: '이 후보 폐기' }))
  expect(updateGeneratedIllustrationReview).toHaveBeenCalledWith(
    'c1',
    'a1',
    { expected_content_revision: 1, expected_asset_revision: 1, discarded: true },
    expect.any(AbortSignal),
  )
  await waitFor(() => expect(screen.queryByRole('img')).not.toBeInTheDocument())
  expect(screen.getByRole('status')).toHaveTextContent(/이용량은 반환되지 않습니다/)
})

it('검토 CAS 충돌 뒤 최신 자산을 재조회한다', async () => {
  const user = userEvent.setup()
  vi.mocked(getGeneratedIllustrations)
    .mockResolvedValueOnce({ content_revision: 1, assets: [asset] })
    .mockResolvedValue({
      content_revision: 1,
      assets: [{ ...asset, alt_text: '다른 화면의 설명', asset_revision: 2 }],
    })
  vi.mocked(updateGeneratedIllustrationReview).mockRejectedValue(new ApiError(409, '충돌'))
  render(<IllustrationGenerationPanel {...props} />)
  await user.click(await screen.findByRole('button', { name: '대체텍스트 수정' }))
  const input = screen.getByLabelText('그림 1 대체텍스트 수정')
  await user.clear(input)
  await user.type(input, '내 수정')
  await user.click(screen.getByRole('button', { name: '대체텍스트 저장' }))
  expect(await screen.findByText('대체텍스트: 다른 화면의 설명')).toBeInTheDocument()
  expect(getGeneratedIllustrations).toHaveBeenCalledTimes(2)
})
