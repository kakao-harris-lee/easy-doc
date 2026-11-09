import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getGeneratedIllustrationImage } from '../api/client'
import type { GeneratedIllustration } from '../api/types'
import { GeneratedIllustrationImage } from './GeneratedIllustrationImage'
import { GeneratedIllustrationPreview } from './GeneratedIllustrationPreview'

vi.mock('../api/client', () => ({ getGeneratedIllustrationImage: vi.fn() }))

const asset: GeneratedIllustration = {
  asset_id: 'a1',
  job_id: 'j1',
  suggestion_id: 's1',
  analysis_version: 'v1',
  configuration_revision: 'a'.repeat(64),
  based_on_content_revision: 1,
  stale: false,
  applied: true,
  discarded: false,
  asset_revision: 1,
  purpose: 'procedure',
  reason: '절차',
  body_range: { start: 0, end: 0 },
  source_anchors: [{ source_unit_indexes: [0], quote: '신청' }],
  scenes: ['신청'],
  preserved_facts: [],
  alt_text: '신청하는 사람',
  provenance: 'provider',
  image_url: '/image',
  download_url: '/download',
  created_at: '2026-01-01',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.mocked(getGeneratedIllustrationImage).mockReset()
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:test'),
    revokeObjectURL: vi.fn(),
  })
})

afterEach(() => vi.unstubAllGlobals())

describe('GeneratedIllustrationImage', () => {
  it('로딩을 live status로 알리고 완료된 이미지에 대체텍스트를 제공한다', async () => {
    const response = deferred<Blob>()
    vi.mocked(getGeneratedIllustrationImage).mockReturnValue(response.promise)
    render(<GeneratedIllustrationImage conversionId="c1" asset={asset} />)

    expect(screen.getByRole('status')).toHaveTextContent('그림을 불러오는 중')
    await act(async () => {
      response.resolve(new Blob(['png']))
      await response.promise
    })

    expect(await screen.findByRole('img', { name: '신청하는 사람' })).toHaveAttribute(
      'src',
      'blob:test',
    )
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('실패 오류를 한 번 알리고 오류와 연결된 재시도를 키보드로 실행한다', async () => {
    const user = userEvent.setup()
    const retryResponse = deferred<Blob>()
    vi.mocked(getGeneratedIllustrationImage)
      .mockRejectedValueOnce(new Error('network'))
      .mockReturnValueOnce(retryResponse.promise)
    render(<GeneratedIllustrationImage conversionId="c1" asset={asset} />)

    const error = await screen.findByRole('alert')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    const retry = screen.getByRole('button', { name: '미리보기 다시 불러오기' })
    expect(retry).toHaveAttribute('aria-describedby', error.id)
    retry.focus()
    await user.keyboard('{Enter}')

    expect(screen.getByRole('status')).toHaveTextContent('그림을 불러오는 중')
    await act(async () => {
      retryResponse.resolve(new Blob(['png']))
      await retryResponse.promise
    })
    expect(await screen.findByRole('img', { name: '신청하는 사람' })).toBeInTheDocument()
    expect(getGeneratedIllustrationImage).toHaveBeenCalledTimes(2)
  })

  it('같은 문서·자산의 요청과 Object URL을 공유하고 마지막 참조에서만 해제한다', async () => {
    const response = deferred<Blob>()
    vi.mocked(getGeneratedIllustrationImage).mockReturnValue(response.promise)
    const view = render(
      <>
        <GeneratedIllustrationImage conversionId="c1" asset={asset} />
        <GeneratedIllustrationImage conversionId="c1" asset={asset} />
      </>,
    )

    expect(getGeneratedIllustrationImage).toHaveBeenCalledTimes(1)
    await act(async () => {
      response.resolve(new Blob(['png']))
      await response.promise
    })
    expect(await screen.findAllByRole('img', { name: '신청하는 사람' })).toHaveLength(2)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)

    view.rerender(<GeneratedIllustrationImage conversionId="c1" asset={asset} />)
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    view.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test')
  })

  it('같은 자산이라도 다른 문서의 인증 응답은 공유하지 않는다', async () => {
    vi.mocked(getGeneratedIllustrationImage).mockImplementation(
      async (conversionId) => new Blob([conversionId]),
    )
    render(
      <>
        <GeneratedIllustrationImage conversionId="c1" asset={asset} />
        <GeneratedIllustrationImage conversionId="c2" asset={asset} />
      </>,
    )

    expect(await screen.findAllByRole('img', { name: '신청하는 사람' })).toHaveLength(2)
    expect(getGeneratedIllustrationImage).toHaveBeenCalledTimes(2)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(2)
  })

  it('공유된 실패를 재시도하면 실패 캐시만 무효화하고 새 요청도 공유한다', async () => {
    const user = userEvent.setup()
    const retryResponse = deferred<Blob>()
    vi.mocked(getGeneratedIllustrationImage)
      .mockRejectedValueOnce(new Error('network'))
      .mockReturnValueOnce(retryResponse.promise)
    render(
      <>
        <GeneratedIllustrationImage conversionId="c1" asset={asset} />
        <GeneratedIllustrationImage conversionId="c1" asset={asset} />
      </>,
    )

    expect(await screen.findAllByRole('alert')).toHaveLength(2)
    await user.click(screen.getAllByRole('button', { name: '미리보기 다시 불러오기' })[0]!)
    expect(getGeneratedIllustrationImage).toHaveBeenCalledTimes(2)
    await act(async () => {
      retryResponse.resolve(new Blob(['png']))
      await retryResponse.promise
    })
    expect(await screen.findAllByRole('img', { name: '신청하는 사람' })).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: '미리보기 다시 불러오기' }))
    expect(await screen.findAllByRole('img', { name: '신청하는 사람' })).toHaveLength(2)
    expect(getGeneratedIllustrationImage).toHaveBeenCalledTimes(2)
  })

  it('문서 전환 뒤 늦은 이전 응답이 새 문서의 로딩이나 이미지를 덮지 않는다', async () => {
    const oldResponse = deferred<Blob>()
    const newResponse = deferred<Blob>()
    vi.mocked(getGeneratedIllustrationImage).mockImplementation((conversionId) =>
      conversionId === 'c1' ? oldResponse.promise : newResponse.promise,
    )
    const view = render(<GeneratedIllustrationImage conversionId="c1" asset={asset} />)
    const oldSignal = vi.mocked(getGeneratedIllustrationImage).mock.calls[0]?.[2]

    view.rerender(<GeneratedIllustrationImage conversionId="c2" asset={asset} />)
    expect(oldSignal?.aborted).toBe(true)
    const oldBlob = new Blob(['old'])
    await act(async () => {
      oldResponse.resolve(oldBlob)
      await oldResponse.promise
    })
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent('그림을 불러오는 중')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()

    const newBlob = new Blob(['new'])
    await act(async () => {
      newResponse.resolve(newBlob)
      await newResponse.promise
    })
    expect(await screen.findByRole('img', { name: '신청하는 사람' })).toHaveAttribute(
      'src',
      'blob:test',
    )
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    expect(URL.createObjectURL).toHaveBeenCalledWith(newBlob)
  })

  it('로드된 이미지의 문서가 바뀌면 이전 Object URL을 즉시 해제한다', async () => {
    const newResponse = deferred<Blob>()
    vi.mocked(getGeneratedIllustrationImage)
      .mockResolvedValueOnce(new Blob(['old']))
      .mockReturnValueOnce(newResponse.promise)
    const view = render(<GeneratedIllustrationImage conversionId="c1" asset={asset} />)
    expect(await screen.findByRole('img', { name: '신청하는 사람' })).toBeInTheDocument()

    view.rerender(<GeneratedIllustrationImage conversionId="c2" asset={asset} />)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test')
    expect(screen.getByRole('status')).toHaveTextContent('그림을 불러오는 중')
  })
})

describe('GeneratedIllustrationPreview', () => {
  it('figcaption과 같은 대체텍스트는 이미지 accessible name에서 제거한다', async () => {
    vi.mocked(getGeneratedIllustrationImage).mockResolvedValue(new Blob(['png']))
    const { container } = render(
      <GeneratedIllustrationPreview
        conversionId="c1"
        savedBody="먼저 신청하세요."
        assets={[asset]}
        blocked={false}
      />,
    )

    await waitFor(() => expect(container.querySelector('img')).toHaveAttribute('src', 'blob:test'))
    expect(container.querySelector('img')).toHaveAttribute('alt', '')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('신청하는 사람').tagName).toBe('FIGCAPTION')
  })
})
