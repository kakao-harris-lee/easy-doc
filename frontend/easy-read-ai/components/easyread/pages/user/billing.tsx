'use client'

import * as React from 'react'
import { PageHeader, StatTile } from '../../ui/layout-bits'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../../ui/card'
import { Button } from '../../ui/button'
import { Badge } from '../../ui/badge'
import { Progress } from '../../ui/progress'
import { StatusMessage } from '../../ui/feedback'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { IconButton } from '../../ui/icon-button'
import { Coins, CreditCard, Users, Download, ArrowUpRight } from 'lucide-react'
import {
  usageSummary,
  monthlyUsage,
  invoices,
  formatKRW,
  formatNum,
} from '../../lib/mock-data'

export function BillingPage() {
  const usedPct = Math.round(
    ((usageSummary.tokenMonthlyQuota - usageSummary.tokenBalance) /
      usageSummary.tokenMonthlyQuota) *
      100,
  )
  const maxUsage = Math.max(...monthlyUsage.map((m) => m.tokens))
  const low = usageSummary.tokenBalance < usageSummary.tokenMonthlyQuota * 0.5

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="요금 및 사용량"
        description="기관의 토큰 잔액, 이번 달 사용량, 청구 내역을 확인합니다."
        actions={
          <Button>
            <ArrowUpRight className="size-4" aria-hidden="true" />
            요금제 업그레이드
          </Button>
        }
      />

      {low && (
        <StatusMessage tone="warning" title="토큰 잔액이 절반 이하입니다">
          이번 달 남은 토큰이 {formatNum(usageSummary.tokenBalance)}개입니다. 부족이
          예상되면 미리 충전하거나 요금제를 상향해 주세요.
        </StatusMessage>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="현재 요금제"
          value={usageSummary.plan}
          sub={`갱신일 ${usageSummary.renewsOn}`}
          icon={CreditCard}
        />
        <StatTile
          label="이번 달 청구액"
          value={formatKRW(usageSummary.monthlySpend)}
          sub="부가세 별도"
          icon={Coins}
        />
        <StatTile
          label="사용 좌석"
          value={`${usageSummary.seats.used} / ${usageSummary.seats.total}`}
          sub={`${usageSummary.seats.total - usageSummary.seats.used}석 남음`}
          icon={Users}
        />
        <StatTile
          label="토큰 잔액"
          value={formatNum(usageSummary.tokenBalance)}
          sub={`월 한도 ${formatNum(usageSummary.tokenMonthlyQuota)}`}
          icon={Coins}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle as="h2">이번 달 토큰 사용률</CardTitle>
            <CardDescription>
              월 한도 {formatNum(usageSummary.tokenMonthlyQuota)} 토큰 기준
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Progress
              value={usedPct}
              tone={usedPct > 80 ? 'danger' : usedPct > 50 ? 'warning' : 'primary'}
              label="사용량"
              showValue
            />
            <p className="text-sm leading-relaxed text-muted-foreground">
              지금까지{' '}
              <b className="text-foreground">
                {formatNum(usageSummary.tokenMonthlyQuota - usageSummary.tokenBalance)}
              </b>{' '}
              토큰을 사용했고,{' '}
              <b className="text-foreground">{formatNum(usageSummary.tokenBalance)}</b>{' '}
              토큰이 남아 있습니다.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle as="h2">월별 사용 추이</CardTitle>
            <CardDescription>최근 6개월간 소비한 토큰</CardDescription>
          </CardHeader>
          <CardContent>
            <div
              className="flex h-48 items-end justify-between gap-2"
              role="img"
              aria-label={`월별 토큰 사용량: ${monthlyUsage
                .map((m) => `${m.month} ${formatNum(m.tokens)}`)
                .join(', ')}`}
            >
              {monthlyUsage.map((m) => (
                <div key={m.month} className="flex flex-1 flex-col items-center gap-2">
                  <div className="flex w-full flex-1 items-end">
                    <div
                      className="w-full rounded-t-md bg-primary/80 transition-all hover:bg-primary"
                      style={{ height: `${(m.tokens / maxUsage) * 100}%` }}
                    />
                  </div>
                  <span className="text-xs font-medium text-muted-foreground">{m.month}</span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle as="h2">청구 내역</CardTitle>
          <CardDescription>발행된 세금계산서를 내려받을 수 있습니다.</CardDescription>
        </CardHeader>
        <CardContent className="px-0 py-0">
          <Table caption="청구 내역 목록" className="text-left">
            <THead>
              <TR>
                <TH>청구번호</TH>
                <TH>청구 기간</TH>
                <TH className="text-right">금액</TH>
                <TH>상태</TH>
                <TH><span className="sr-only">동작</span></TH>
              </TR>
            </THead>
            <TBody>
              {invoices.map((inv) => (
                <TR key={inv.id}>
                  <TD className="font-mono text-sm text-muted-foreground">{inv.id}</TD>
                  <TD className="font-semibold text-foreground">{inv.period}</TD>
                  <TD className="text-right tabular-nums">{formatKRW(inv.amount)}</TD>
                  <TD><Badge tone="success">결제 완료</Badge></TD>
                  <TD>
                    <IconButton label="세금계산서 내려받기" size="sm">
                      <Download className="size-5" aria-hidden="true" />
                    </IconButton>
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
