import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, getIllustrations, illustrationImageUrl } from '../api/client'
import type { Illustration } from '../api/types'
import { IllustrationsPanel } from './IllustrationsPanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getIllustrations: vi.fn(),
}))

function illustration(overrides: Partial<Illustration> = {}): Illustration {
  return {
    asset_id: 'visit-office',
    caption: '기관 방문',
    purpose: 'visit_office',
    alt_text: '사람이 건물 입구로 걸어 들어가는 그림',
    license: 'CC0-1.0',
    source: 'easy-doc 저장소에서 직접 제작(…)',
    reviewed_by: 'harris.lee',
    reviewed_at: '2026-09-23',
    version: 1,
    mapping_examples: ['주민센터에 직접 가서 신청하세요.'],
    image_url: '/illustrations/visit-office/image',
    ...overrides,
  }
}

beforeEach(() => {
  vi.mocked(getIllustrations).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('IllustrationsPanel', () => {
  it('로딩 뒤 카드 목록을 표시한다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })

    render(<IllustrationsPanel />)

    expect(screen.getByText('그림 목록을 불러오는 중…')).toBeInTheDocument()

    const img = await screen.findByRole('img', {
      name: '사람이 건물 입구로 걸어 들어가는 그림',
    })
    expect(img).toHaveAttribute('src', illustrationImageUrl('/illustrations/visit-office/image'))
    // 캡션 "기관 방문"과 purpose 라벨 "기관 방문"이 이 고정값에서는 같은 문자열이라 두
    // 곳에 나타난다(캡션 span, 용도 span).
    expect(screen.getAllByText('기관 방문').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/사람이 건물 입구로 걸어 들어가는 그림/)).toBeInTheDocument()
    expect(screen.getByText('주민센터에 직접 가서 신청하세요.')).toBeInTheDocument()
    expect(screen.getByText(/CC0-1\.0/)).toBeInTheDocument()
    expect(screen.getByText(/harris\.lee/)).toBeInTheDocument()
    expect(screen.getByText(/2026-09-23/)).toBeInTheDocument()
    expect(screen.getByText(/v1/)).toBeInTheDocument()
  })

  it('안내문(ER-16 미구현 고지)이 항상 표시된다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [] })

    render(<IllustrationsPanel />)

    await waitFor(() =>
      expect(
        screen.getByText(/본문에 넣기와 파일 출력은 다음 단계에서 지원합니다/),
      ).toBeInTheDocument(),
    )
  })

  it('목록이 비어 있으면 안내문만 보인다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [] })

    render(<IllustrationsPanel />)

    await waitFor(() => expect(screen.getByText('검수된 그림이 없습니다.')).toBeInTheDocument())
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('불러오기 실패는 ApiError 메시지를 alert로 보여준다', async () => {
    vi.mocked(getIllustrations).mockRejectedValue(
      new ApiError(500, '그림 목록을 읽을 수 없습니다.'),
    )

    render(<IllustrationsPanel />)

    expect(await screen.findByRole('alert')).toHaveTextContent('그림 목록을 읽을 수 없습니다.')
  })

  it('모르는 purpose는 wire 값을 그대로 보여준다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({
      illustrations: [illustration({ purpose: 'future_purpose' })],
    })

    render(<IllustrationsPanel />)

    expect(await screen.findByText('future_purpose')).toBeInTheDocument()
  })
})
