import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { FormatPreservation } from '../api/types'
import { FormatPreservationPanel } from './FormatPreservationPanel'

describe('원본 서식 유지 패널', () => {
  it('계약 2.16.0 — `available`도 details 1건을 그대로 보여준다', () => {
    // segment_map이 놓은 합침·나눔은 대응을 확신한 반영이라 `available`을 깨지 않지만,
    // 무슨 일이 있었는지는 details로 말한다(계약 2.16.0, FormatPreservationStatus.available).
    const preservation: FormatPreservation = {
      status: 'available',
      details: ['원본 문단 2개는 앞 문단과 합쳐져 빈 문단으로 남습니다.'],
    }

    render(<FormatPreservationPanel sourceFormat="docx" preservation={preservation} />)

    expect(screen.getByRole('region', { name: '원본 서식 유지' })).toBeInTheDocument()
    expect(screen.getByText('유지 가능')).toBeInTheDocument()
    expect(screen.getByRole('listitem')).toHaveTextContent(
      '원본 문단 2개는 앞 문단과 합쳐져 빈 문단으로 남습니다.',
    )
  })

  it('`available` + 빈 details는 목록을 그리지 않는다', () => {
    const preservation: FormatPreservation = { status: 'available', details: [] }

    render(<FormatPreservationPanel sourceFormat="docx" preservation={preservation} />)

    expect(screen.getByRole('region', { name: '원본 서식 유지' })).toBeInTheDocument()
    expect(screen.getByText('유지 가능')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })
})
