'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card } from '../../ui/card'
import { Button } from '../../ui/button'
import { Input } from '../../ui/field'
import { Badge } from '../../ui/badge'
import { Tabs, TabPanel } from '../../ui/tabs'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { Skeleton, SkeletonBlock } from '../../ui/skeleton'
import { EmptyState } from '../../ui/feedback'
import { Dialog } from '../../ui/dialog'
import { IconButton } from '../../ui/icon-button'
import { Search, Eye, FileSearch, Download, FilePlus2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import {
  conversions,
  conversionStatusLabel,
  conversionStatusTone,
  formatNum,
  SAMPLE_SOURCE_TEXT,
  SAMPLE_EASY_TEXT,
  type Conversion,
} from '../../lib/mock-data'

const filters = [
  { value: 'all', label: '전체' },
  { value: 'review', label: '검토 중' },
  { value: 'draft', label: '검토 대기' },
  { value: 'published', label: '발행 완료' },
  { value: 'failed', label: '변환 실패' },
]

export function HistoryPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = React.useState(true)
  const [tab, setTab] = React.useState('all')
  const [query, setQuery] = React.useState('')
  const [selected, setSelected] = React.useState<Conversion | null>(null)

  React.useEffect(() => {
    const t = window.setTimeout(() => setLoading(false), 900)
    return () => window.clearTimeout(t)
  }, [])

  const filtered = conversions.filter((c) => {
    const matchTab = tab === 'all' || c.status === tab
    const matchQuery =
      query.trim() === '' ||
      c.title.toLowerCase().includes(query.toLowerCase()) ||
      c.id.toLowerCase().includes(query.toLowerCase())
    return matchTab && matchQuery
  })

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="변환 기록"
        description="지금까지 변환한 문서를 확인하고 다시 열어볼 수 있습니다."
        actions={
          <Button onClick={() => navigate('/app')}>
            <FilePlus2 className="size-4" aria-hidden="true" />
            새 변환
          </Button>
        }
      />

      <Card className="overflow-hidden">
        <div className="flex flex-col gap-4 p-4 sm:p-5">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <Tabs items={filters} value={tab} onValueChange={setTab} idBase="history" className="border-0" />
            <div className="relative w-full sm:w-72">
              <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <label htmlFor="history-search" className="sr-only">문서 검색</label>
              <Input
                id="history-search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="제목 또는 문서번호 검색"
                className="pl-9"
              />
            </div>
          </div>

          <TabPanel value={tab} active idBase="history">
            {loading ? (
              <SkeletonBlock className="flex flex-col gap-2 py-2">
                {Array.from({ length: 5 }).map((_, i) => (
                  <div key={i} className="flex items-center gap-4 rounded-lg border border-border p-3">
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-4 flex-1" />
                    <Skeleton className="h-6 w-20 rounded-full" />
                    <Skeleton className="h-4 w-16" />
                  </div>
                ))}
              </SkeletonBlock>
            ) : filtered.length === 0 ? (
              <EmptyState
                icon={FileSearch}
                title="결과가 없습니다"
                description="검색어나 필터 조건에 맞는 변환 기록이 없습니다. 조건을 바꿔 다시 찾아보세요."
                action={
                  <Button variant="outline" onClick={() => { setQuery(''); setTab('all') }}>
                    필터 초기화
                  </Button>
                }
              />
            ) : (
              <Table caption="변환 기록 목록">
                <THead>
                  <TR>
                    <TH>문서번호</TH>
                    <TH>제목</TH>
                    <TH>분류</TH>
                    <TH>상태</TH>
                    <TH className="text-right">토큰</TH>
                    <TH>작성일</TH>
                    <TH><span className="sr-only">동작</span></TH>
                  </TR>
                </THead>
                <TBody>
                  {filtered.map((c) => (
                    <TR key={c.id}>
                      <TD className="font-mono text-sm text-muted-foreground">{c.id}</TD>
                      <TD className="font-semibold text-foreground">{c.title}</TD>
                      <TD className="text-muted-foreground">{c.category}</TD>
                      <TD>
                        <Badge tone={conversionStatusTone[c.status]}>
                          {conversionStatusLabel[c.status]}
                        </Badge>
                      </TD>
                      <TD className="text-right tabular-nums">
                        {c.tokens ? formatNum(c.tokens) : '—'}
                      </TD>
                      <TD className="whitespace-nowrap text-muted-foreground">{c.createdAt}</TD>
                      <TD>
                        <IconButton label="상세 보기" size="sm" onClick={() => setSelected(c)}>
                          <Eye className="size-5" aria-hidden="true" />
                        </IconButton>
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </TabPanel>
        </div>
      </Card>

      <Dialog
        open={selected !== null}
        onClose={() => setSelected(null)}
        size="lg"
        title={selected?.title ?? ''}
        description={selected ? `${selected.id} · ${selected.category} · ${selected.author}` : ''}
        footer={
          <>
            <Button variant="ghost" onClick={() => setSelected(null)}>닫기</Button>
            <Button variant="outline">
              <Download className="size-4" aria-hidden="true" />
              내려받기
            </Button>
          </>
        }
      >
        {selected && (
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={conversionStatusTone[selected.status]}>
                {conversionStatusLabel[selected.status]}
              </Badge>
              <Badge tone="neutral" withIcon={false}>읽기 수준 · {selected.readingGrade}</Badge>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <div className="flex flex-col gap-2">
                <h3 className="text-sm font-bold text-muted-foreground">원문</h3>
                <div className="max-h-64 overflow-auto rounded-[10px] border border-border bg-secondary p-3 text-sm leading-relaxed text-foreground whitespace-pre-line">
                  {SAMPLE_SOURCE_TEXT}
                </div>
              </div>
              <div className="flex flex-col gap-2">
                <h3 className="text-sm font-bold text-primary">쉬운 우리말</h3>
                <div className="max-h-64 overflow-auto rounded-[10px] border border-[color:var(--primary)]/25 bg-accent p-3 text-sm leading-relaxed text-foreground whitespace-pre-line">
                  {selected.status === 'failed' ? '변환에 실패한 문서입니다.' : SAMPLE_EASY_TEXT}
                </div>
              </div>
            </div>
          </div>
        )}
      </Dialog>
    </div>
  )
}
