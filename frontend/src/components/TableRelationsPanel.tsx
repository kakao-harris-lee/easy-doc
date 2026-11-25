import { useId } from 'react'

import type { TableCellStructure, TableStructure } from '../api/types'

interface TableRelationsPanelProps {
  sourceText: string
  tables: TableStructure[] | null | undefined
}

const SUPPORT_REASON_TEXT: Record<NonNullable<TableStructure['support_reason']>, string> = {
  merged_cells: '병합된 셀이 있어 표의 관계를 자동으로 연결하지 못했습니다.',
  nested_table: '중첩된 표가 있어 표의 관계를 자동으로 연결하지 못했습니다.',
  irregular_grid: '표의 행·열 구조가 일정하지 않아 관계를 자동으로 연결하지 못했습니다.',
  multiple_header_rows: '열 제목이 여러 줄이라 관계를 자동으로 연결하지 못했습니다.',
  header_row_missing: '열 제목을 확인할 수 없어 관계를 자동으로 연결하지 못했습니다.',
  limit_exceeded: '표가 지원 범위를 넘어 관계를 자동으로 연결하지 못했습니다.',
  coordinates_lost: '원문 위치를 잃어 표의 관계를 자동으로 연결하지 못했습니다.',
  historical_document: '이전 문서에는 표 관계 정보가 없어 표시할 수 없습니다.',
  unsupported_format: '이 문서 형식의 표 관계는 아직 지원하지 않습니다.',
}

function sourceExcerpt(lines: string[], indexes: number[]): string | null {
  if (indexes.length === 0) return null
  const excerpts = indexes.map((index) => (Number.isInteger(index) ? lines[index] : undefined))
  return excerpts.every((line): line is string => line !== undefined) ? excerpts.join('\n') : null
}

function tableFailureText(table: TableStructure): string {
  if (table.support_reason !== null) return SUPPORT_REASON_TEXT[table.support_reason]
  return '이 표의 관계를 자동으로 연결하지 못했습니다. 원문에서 직접 확인해 주세요.'
}

function cellValue(lines: string[], cell: TableCellStructure): string | null {
  return sourceExcerpt(lines, cell.source_unit_indexes)
}

function cellHeader(lines: string[], cell: TableCellStructure): string | null {
  return sourceExcerpt(lines, cell.header_refs)
}

function SupportedTable({ table, lines }: { table: TableStructure; lines: string[] }) {
  const cells = table.cells
  const rows = Array.from({ length: table.row_count }, (_, row) =>
    cells.filter((cell) => cell.row === row).sort((left, right) => left.column - right.column),
  )
  const complete =
    table.row_count > 0 &&
    table.column_count > 0 &&
    rows.every(
      (row) =>
        row.length === table.column_count &&
        row.every(
          (cell) =>
            cell.column >= 0 && cell.column < table.column_count && cellValue(lines, cell) !== null,
        ),
    )
  const unit = sourceExcerpt(lines, table.unit_anchors)
  const footnote = sourceExcerpt(lines, table.footnote_anchors)

  if (!complete || sourceExcerpt(lines, table.source_unit_indexes) === null) {
    return (
      <p className="mt-3 rounded-[10px] border border-info/25 bg-info-surface p-3 text-sm text-info">
        표의 원문 위치를 확인할 수 없습니다. 원문에서 직접 확인해 주세요.
      </p>
    )
  }

  return (
    <>
      <div className="mt-3 overflow-x-auto rounded-[10px] border border-border">
        <table className="min-w-full border-collapse text-left text-sm">
          <caption className="sr-only">{table.table_id} 표의 행과 열</caption>
          <thead className="bg-muted">
            <tr>
              {rows[0]?.map((cell) => (
                <th
                  className="border-b border-border px-3 py-2 align-top font-semibold"
                  key={cell.column}
                >
                  {cellValue(lines, cell)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.slice(1).map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((cell) => {
                  const header = cellHeader(lines, cell)
                  return (
                    <td className="border-b border-border px-3 py-2 align-top" key={cell.column}>
                      <span className="block whitespace-pre-wrap">{cellValue(lines, cell)}</span>
                      {header !== null && (
                        <span className="mt-1 block text-xs text-muted-foreground">
                          열 제목: {header}
                        </span>
                      )}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {unit !== null && (
        <p className="mt-2 text-sm text-muted-foreground">
          <span className="sr-only">단위: </span>
          <span className="whitespace-pre-wrap">{unit}</span>
        </p>
      )}
      {footnote !== null && (
        <p className="mt-1 text-sm text-muted-foreground">
          <span className="sr-only">각주: </span>
          <span className="whitespace-pre-wrap">{footnote}</span>
        </p>
      )}
    </>
  )
}

/** R4가 켜진 문서에서 서버가 확인한 표 관계만 보여준다. */
export function TableRelationsPanel({ sourceText, tables }: TableRelationsPanelProps) {
  const headingId = useId()
  const lines = sourceText.split(/\r?\n/)

  return (
    <section
      className="mt-5 rounded-[12px] border border-border bg-card p-4"
      aria-labelledby={headingId}
    >
      <h2 id={headingId} className="text-base font-bold">
        표 관계
      </h2>
      {tables === null || tables === undefined ? (
        <p className="mt-2 text-sm text-muted-foreground">
          이 문서의 표 관계 정보를 확인할 수 없습니다. 원문에서 직접 확인해 주세요.
        </p>
      ) : tables.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">원문에서 확인된 표가 없습니다.</p>
      ) : (
        <div className="mt-4 flex flex-col gap-5">
          {tables.map((table) => (
            <article key={table.table_id} aria-labelledby={`${headingId}-${table.table_id}`}>
              <h3 id={`${headingId}-${table.table_id}`} className="font-semibold">
                {table.table_id}
              </h3>
              {table.support_status === 'supported' ? (
                <SupportedTable table={table} lines={lines} />
              ) : (
                <p className="mt-3 rounded-[10px] border border-info/25 bg-info-surface p-3 text-sm text-info">
                  {table.support_status === 'unavailable'
                    ? '이 문서의 표 관계 정보를 확인할 수 없습니다. 원문에서 직접 확인해 주세요.'
                    : tableFailureText(table)}
                </p>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
