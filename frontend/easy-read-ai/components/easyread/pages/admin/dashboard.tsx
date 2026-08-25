'use client'

import * as React from 'react'
import { PageHeader, StatTile } from '../../ui/layout-bits'
import { Card, CardHeader, CardTitle, CardContent } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import {
  Building2,
  Coins,
  Repeat,
  FileText,
  TrendingUp,
} from 'lucide-react'
import {
  customers,
  adminUsage,
  monthlyUsage,
  formatKRW,
  formatNum,
} from '../../lib/mock-data'

const customerStatusTone: Record<string, 'success' | 'info' | 'danger' | 'warning'> = {
  정상: 'success',
  체험: 'info',
  연체: 'danger',
  '해지 예정': 'warning',
}

function UsageBars() {
  const max = Math.max(...monthlyUsage.map((m) => m.tokens))
  return (
    <div className="flex items-end gap-3" role="img" aria-label="최근 6개월 토큰 사용량 추이">
      {monthlyUsage.map((m) => (
        <div key={m.month} className="flex flex-1 flex-col items-center gap-2">
          <div className="flex h-40 w-full items-end justify-center">
            <div
              className="w-full max-w-10 rounded-t-md bg-primary/85"
              style={{ height: `${Math.round((m.tokens / max) * 100)}%` }}
            />
          </div>
          <span className="text-xs font-medium text-muted-foreground">{m.month}</span>
          <span className="text-xs font-semibold tabular-nums text-foreground">
            {Math.round(m.tokens / 1000)}K
          </span>
        </div>
      ))}
    </div>
  )
}

export function AdminDashboard() {
  const totalMrr = customers.reduce((s, c) => s + c.mrr, 0)
  const totalSeats = customers.reduce((s, c) => s + c.seats, 0)

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="운영 대시보드"
        description="플랫폼 전반의 고객·구독·사용량 현황을 확인합니다."
      />

      <section aria-label="핵심 지표" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="고객 기관" value={`${customers.length}곳`} icon={Building2} sub="이번 달 신규 1곳" />
        <StatTile label="월 반복 매출(MRR)" value={formatKRW(totalMrr)} icon={Coins} sub="전월 대비 +6.2%" />
        <StatTile label="활성 구독" value="4건" icon={Repeat} sub="취소 예정 1건" />
        <StatTile label="누적 좌석" value={`${totalSeats}석`} icon={FileText} sub="사용률 78%" />
      </section>

      <div className="grid gap-6 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader className="flex-row items-center justify-between">
            <div>
              <CardTitle>월별 토큰 사용량</CardTitle>
            </div>
            <Badge tone="success">
              <TrendingUp className="size-3.5" aria-hidden="true" />
              증가세
            </Badge>
          </CardHeader>
          <CardContent>
            <UsageBars />
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>사용량 상위 기관</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {adminUsage.slice(0, 4).map((row, i) => (
              <div key={row.org} className="flex items-center gap-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-secondary text-sm font-bold text-foreground tabular-nums">
                  {i + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-foreground">{row.org}</p>
                  <p className="text-xs text-muted-foreground tabular-nums">
                    {formatNum(row.tokens)} 토큰 · {row.conversions}건
                  </p>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>최근 고객 기관</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table caption="최근 고객 기관 목록">
            <THead>
              <TR>
                <TH>기관</TH>
                <TH>요금제</TH>
                <TH className="text-right">좌석</TH>
                <TH className="text-right">MRR</TH>
                <TH>상태</TH>
              </TR>
            </THead>
            <TBody>
              {customers.map((c) => (
                <TR key={c.id}>
                  <TD className="font-semibold">{c.org}</TD>
                  <TD className="text-muted-foreground">{c.plan}</TD>
                  <TD className="text-right tabular-nums">{c.seats}</TD>
                  <TD className="text-right tabular-nums">{formatKRW(c.mrr)}</TD>
                  <TD>
                    <Badge tone={customerStatusTone[c.status]}>{c.status}</Badge>
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
