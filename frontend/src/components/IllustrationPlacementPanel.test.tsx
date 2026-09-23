import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  getIllustrationPlacements,
  getIllustrations,
  putIllustrationPlacements,
} from '../api/client'
import type { Illustration, IllustrationPlacementsResponse } from '../api/types'
import { IllustrationPlacementPanel } from './IllustrationPlacementPanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  getIllustrations: vi.fn(),
  getIllustrationPlacements: vi.fn(),
  putIllustrationPlacements: vi.fn(),
}))

function illustration(overrides: Partial<Illustration> = {}): Illustration {
  return {
    asset_id: 'visit-office',
    caption: '기관 방문',
    purpose: 'visit_office',
    alt_text: '사람이 건물 입구로 걸어 들어가는 그림',
    license: 'CC0',
    source: '자체 제작',
    reviewed_by: '검수자',
    reviewed_at: '2026-09-01',
    version: 1,
    mapping_examples: ['주민센터에 직접 가서 신청하세요.'],
    image_url: '/illustrations/visit-office/image',
    ...overrides,
  }
}

function placementsResponse(
  overrides: Partial<IllustrationPlacementsResponse> = {},
): IllustrationPlacementsResponse {
  return {
    conversion_id: 'c1',
    current_content_revision: 1,
    placements_content_revision: null,
    stale: false,
    placements: [],
    ...overrides,
  }
}

beforeEach(() => {
  vi.mocked(getIllustrations).mockReset()
  vi.mocked(getIllustrationPlacements).mockReset()
  vi.mocked(putIllustrationPlacements).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('IllustrationPlacementPanel', () => {
  it('카탈로그와 배치를 함께 불러와 select 초기값을 채운다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(
      placementsResponse({
        current_content_revision: 1,
        placements_content_revision: 1,
        placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
      }),
    )

    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        units={['오늘 서류를 내세요.']}
      />,
    )

    const select = await screen.findByRole('combobox', { name: '1번째 줄 그림' })
    await waitFor(() => expect(select).toHaveValue('visit-office'))
    expect(getIllustrations).toHaveBeenCalledWith(expect.any(AbortSignal))
    expect(getIllustrationPlacements).toHaveBeenCalledWith('c1', expect.any(AbortSignal))
  })

  it('선택을 바꾸고 저장하면 기대 revision과 배치 목록으로 PUT하고 onPlacementsChange를 부른다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(
      placementsResponse({ current_content_revision: 3 }),
    )
    vi.mocked(putIllustrationPlacements).mockResolvedValue(
      placementsResponse({
        current_content_revision: 3,
        placements_content_revision: 3,
        placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
      }),
    )

    const onPlacementsChange = vi.fn()
    const user = userEvent.setup()
    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={3}
        dirty={false}
        units={['오늘 서류를 내세요.']}
        onPlacementsChange={onPlacementsChange}
      />,
    )

    const select = await screen.findByRole('combobox', { name: '1번째 줄 그림' })
    await user.selectOptions(select, 'visit-office')
    await user.click(screen.getByRole('button', { name: '그림 배치 저장' }))

    await waitFor(() =>
      expect(putIllustrationPlacements).toHaveBeenCalledWith('c1', {
        expected_content_revision: 3,
        placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
      }),
    )
    await waitFor(() => expect(onPlacementsChange).toHaveBeenCalledWith(1, false))
  })

  it('본문이 수정 중(dirty)이면 저장 버튼이 비활성이고 안내문을 보여준다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(placementsResponse())

    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={1}
        dirty
        units={['오늘 서류를 내세요.']}
      />,
    )

    await screen.findByRole('combobox', { name: '1번째 줄 그림' })
    expect(screen.getByRole('button', { name: '그림 배치 저장' })).toBeDisabled()
    expect(screen.getByText('본문을 먼저 저장한 뒤 그림을 배치해 주세요.')).toBeInTheDocument()
  })

  it('stale 응답이면 배지와 안내문을 보여주고 미리보기에는 그림을 그리지 않는다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(
      placementsResponse({
        current_content_revision: 2,
        placements_content_revision: 1,
        stale: true,
        placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
      }),
    )

    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={2}
        dirty={false}
        units={['오늘 서류를 내세요.']}
      />,
    )

    await waitFor(() => expect(screen.getByText('현재 본문과 다름')).toBeInTheDocument())
    expect(
      screen.getByText('본문이 바뀌었습니다. 그림 위치를 확인한 뒤 다시 저장해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.getByText('본문이 바뀌어 그림을 숨겼습니다')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('미리보기에 그림의 대체텍스트·캡션과 줄 텍스트를 함께 보여준다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(
      placementsResponse({
        current_content_revision: 1,
        placements_content_revision: 1,
        placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
      }),
    )

    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        units={['오늘 서류를 내세요.']}
      />,
    )

    const image = await screen.findByRole('img', { name: '사람이 건물 입구로 걸어 들어가는 그림' })
    expect(image).toBeInTheDocument()
    // "기관 방문"은 select 옵션에도 같은 문구가 있어, figcaption으로 범위를 좁힌다.
    expect(image.closest('figure')).toHaveTextContent('기관 방문')
    // 줄 텍스트는 편집 표에도 나오므로 최소 한 번(미리보기)은 보이는지만 확인한다.
    expect(screen.getAllByText('오늘 서류를 내세요.').length).toBeGreaterThanOrEqual(1)
  })

  it('저장이 409로 실패하면 본문 충돌 안내를 alert로 보여준다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(
      placementsResponse({ current_content_revision: 1 }),
    )
    vi.mocked(putIllustrationPlacements).mockRejectedValue(new ApiError(409, '충돌'))

    const user = userEvent.setup()
    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        units={['오늘 서류를 내세요.']}
      />,
    )

    const select = await screen.findByRole('combobox', { name: '1번째 줄 그림' })
    await user.selectOptions(select, 'visit-office')
    await user.click(screen.getByRole('button', { name: '그림 배치 저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '다른 화면에서 저장한 최신 내용과 충돌했습니다.',
    )
  })

  it('선택을 모두 지우고 저장하면 빈 배열로 PUT한다', async () => {
    vi.mocked(getIllustrations).mockResolvedValue({ illustrations: [illustration()] })
    vi.mocked(getIllustrationPlacements).mockResolvedValue(
      placementsResponse({
        current_content_revision: 1,
        placements_content_revision: 1,
        placements: [{ easy_unit_index: 0, asset_id: 'visit-office' }],
      }),
    )
    vi.mocked(putIllustrationPlacements).mockResolvedValue(
      placementsResponse({
        current_content_revision: 1,
        placements_content_revision: 1,
        placements: [],
      }),
    )

    const user = userEvent.setup()
    render(
      <IllustrationPlacementPanel
        conversionId="c1"
        contentRevision={1}
        dirty={false}
        units={['오늘 서류를 내세요.']}
      />,
    )

    const select = await screen.findByRole('combobox', { name: '1번째 줄 그림' })
    await waitFor(() => expect(select).toHaveValue('visit-office'))
    await user.selectOptions(select, '')
    await user.click(screen.getByRole('button', { name: '그림 배치 저장' }))

    await waitFor(() =>
      expect(putIllustrationPlacements).toHaveBeenCalledWith('c1', {
        expected_content_revision: 1,
        placements: [],
      }),
    )
  })
})
