'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { subscriptions, formatKRW } from '../../lib/mock-data'

const statusTone: Record<string, 'success' | 'warning' | 'danger'> = {
  활성: 'success',
  '취소 예정': 'warning',
  정지: 'danger',
}

export function AdminSubscriptions() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="구독 관리"
        description="기관별 구독 상태와 다음 결제 일정을 관리합니다."
      />

      <Card>
        <CardContent className="p-0">
          <Table caption="구독 목록">
            <THead>
              <TR>
                <TH>구독 ID</TH>
                <TH>기관</TH>
                <TH>요금제</TH>
                <TH>주기</TH>
                <TH>다음 결제</TH>
                <TH className="text-right">금액</TH>
                <TH>상태</TH>
              </TR>
            </THead>
            <TBody>
              {subscriptions.map((s) => (
                <TR key={s.id}>
                  <TD className="font-mono text-sm text-muted-foreground">{s.id}</TD>
                  <TD className="font-semibold">{s.org}</TD>
                  <TD className="text-muted-foreground">{s.plan}</TD>
                  <TD>
                    <Badge tone="neutral" withIcon={false}>
                      {s.cycle}
                    </Badge>
                  </TD>
                  <TD className="tabular-nums text-muted-foreground">{s.nextBilling}</TD>
                  <TD className="text-right tabular-nums">{formatKRW(s.amount)}</TD>
                  <TD>
                    <Badge tone={statusTone[s.status]}>{s.status}</Badge>
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
