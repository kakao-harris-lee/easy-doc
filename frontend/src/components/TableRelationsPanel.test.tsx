import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { TableStructure } from '../api/types'
import { TableRelationsPanel } from './TableRelationsPanel'

function supportedTable(overrides: Partial<TableStructure> = {}): TableStructure {
  return {
    table_id: 'table-1',
    source_unit_indexes: [0, 1, 2, 3],
    row_count: 2,
    column_count: 2,
    cells: [
      { row: 0, column: 0, source_unit_indexes: [0], header_refs: [] },
      { row: 0, column: 1, source_unit_indexes: [1], header_refs: [] },
      { row: 1, column: 0, source_unit_indexes: [2], header_refs: [0] },
      { row: 1, column: 1, source_unit_indexes: [3], header_refs: [1] },
    ],
    unit_anchors: [4],
    footnote_anchors: [5],
    support_status: 'supported',
    support_reason: null,
    ...overrides,
  }
}

describe('TableRelationsPanel', () => {
  it('확인된 셀의 원문 줄·열 제목·단위·각주만 표시한다', () => {
    render(
      <TableRelationsPanel
        sourceText={'품목\n금액\n사과\n1000\n단위: 원\n* 세금 포함'}
        tables={[supportedTable()]}
      />,
    )

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '품목' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '금액' })).toBeInTheDocument()
    expect(screen.getByText('사과')).toBeInTheDocument()
    expect(screen.getByText('열 제목: 품목')).toBeInTheDocument()
    expect(screen.getByText('열 제목: 금액')).toBeInTheDocument()
    expect(screen.getByText('단위: 원')).toBeInTheDocument()
    expect(screen.getByText('* 세금 포함')).toBeInTheDocument()
  })

  it('미지원 표는 추측한 행·열 대신 서버 사유를 표시한다', () => {
    render(
      <TableRelationsPanel
        sourceText="병합된 표"
        tables={[
          supportedTable({
            support_status: 'unsupported',
            support_reason: 'merged_cells',
            cells: [],
            source_unit_indexes: [0],
          }),
        ]}
      />,
    )

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(
      screen.getByText('병합된 셀이 있어 표의 관계를 자동으로 연결하지 못했습니다.'),
    ).toBeInTheDocument()
  })

  it('표 정보가 없으면 원문 직접 확인 안내를 표시한다', () => {
    render(<TableRelationsPanel sourceText="원문" tables={null} />)

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByText(/표 관계 정보를 확인할 수 없습니다/)).toBeInTheDocument()
  })
})
