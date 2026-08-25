'use client'

import * as React from 'react'
import { PageHeader, StatTile } from '../../ui/layout-bits'
import { Card, CardContent } from '../../ui/card'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { adminUsage, formatKRW, formatNum } from '../../lib/mock-data'
import { Coins, FileText, Building2 } from 'lucide-react'

export function AdminUsage() {
  const totalTokens = adminUsage.reduce((s, r) => s + r.tokens, 0)
  const totalCost = adminUsage.reduce((s, r) => s + r.cost, 0)
  const totalConv = adminUsage.reduce((s, r) => s + r.conversions, 0)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="사용량·비용"
        description="기관별 토큰 사용량과 청구 비용을 집계합니다."
      />

      <section aria-label="합계" className="grid gap-4 sm:grid-cols-3">
        <StatTile label="총 토큰 사용량" value={formatNum(totalTokens)} icon={Coins} sub="이번 달 누적" />
        <StatTile label="총 청구 비용" value={formatKRW(totalCost)} icon={Building2} sub="이번 달 누적" />
        <StatTile label="총 변환 건수" value={`${formatNum(totalConv)}건`} icon={FileText} sub="이번 달 누적" />
      </section>

      <Card>
        <CardContent className="p-0">
          <Table caption="기관별 사용량 목록">
            <THead>
              <TR>
                <TH>기관</TH>
                <TH className="text-right">토큰</TH>
                <TH className="text-right">변환 건수</TH>
                <TH className="text-right">비용</TH>
              </TR>
            </THead>
            <TBody>
              {adminUsage.map((r) => (
                <TR key={r.org}>
                  <TD className="font-semibold">{r.org}</TD>
                  <TD className="text-right tabular-nums">{formatNum(r.tokens)}</TD>
                  <TD className="text-right tabular-nums">{formatNum(r.conversions)}</TD>
                  <TD className="text-right tabular-nums">{formatKRW(r.cost)}</TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
