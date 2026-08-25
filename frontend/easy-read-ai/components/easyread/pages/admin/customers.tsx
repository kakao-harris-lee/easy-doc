'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Button } from '../../ui/button'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { customers, formatKRW } from '../../lib/mock-data'
import { Search, Plus } from 'lucide-react'

const statusTone: Record<string, 'success' | 'info' | 'danger' | 'warning'> = {
  정상: 'success',
  체험: 'info',
  연체: 'danger',
  '해지 예정': 'warning',
}

export function AdminCustomers() {
  const [q, setQ] = React.useState('')
  const rows = customers.filter((c) => c.org.includes(q) || c.id.includes(q))

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="고객 기관"
        description="서비스를 이용하는 기관을 관리합니다."
        actions={
          <Button size="sm">
            <Plus className="size-4" aria-hidden="true" />
            기관 추가
          </Button>
        }
      />

      <div className="max-w-sm">
        <label htmlFor="cust-search" className="sr-only">
          기관 검색
        </label>
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <input
            id="cust-search"
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="기관명 또는 ID 검색"
            className="h-11 w-full rounded-[10px] border border-input bg-card pl-9 pr-3 text-[15px] text-foreground outline-none placeholder:text-muted-foreground focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
          />
        </div>
      </div>

      <Card>
        <CardContent className="p-0">
          <Table caption="고객 기관 목록">
            <THead>
              <TR>
                <TH>기관 ID</TH>
                <TH>기관명</TH>
                <TH>요금제</TH>
                <TH className="text-right">좌석</TH>
                <TH className="text-right">MRR</TH>
                <TH>상태</TH>
              </TR>
            </THead>
            <TBody>
              {rows.map((c) => (
                <TR key={c.id}>
                  <TD className="font-mono text-sm text-muted-foreground">{c.id}</TD>
                  <TD className="font-semibold">{c.org}</TD>
                  <TD className="text-muted-foreground">{c.plan}</TD>
                  <TD className="text-right tabular-nums">{c.seats}</TD>
                  <TD className="text-right tabular-nums">{formatKRW(c.mrr)}</TD>
                  <TD>
                    <Badge tone={statusTone[c.status]}>{c.status}</Badge>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
