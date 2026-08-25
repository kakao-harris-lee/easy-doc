'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import {
  conversions,
  conversionStatusLabel,
  conversionStatusTone,
  formatNum,
} from '../../lib/mock-data'

export function AdminConversions() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="변환 내역"
        description="모든 기관에서 생성된 변환 문서를 확인합니다."
      />

      <Card>
        <CardContent className="p-0">
          <Table caption="전체 변환 내역">
            <THead>
              <TR>
                <TH>문서 ID</TH>
                <TH>제목</TH>
                <TH>작성자</TH>
                <TH>생성 시각</TH>
                <TH className="text-right">토큰</TH>
                <TH>상태</TH>
              </TR>
            </THead>
            <TBody>
              {conversions.map((c) => (
                <TR key={c.id}>
                  <TD className="font-mono text-sm text-muted-foreground">{c.id}</TD>
                  <TD className="max-w-xs">
                    <span className="line-clamp-1 font-semibold">{c.title}</span>
                  </TD>
                  <TD className="text-muted-foreground">{c.author}</TD>
                  <TD className="tabular-nums text-muted-foreground">{c.createdAt}</TD>
                  <TD className="text-right tabular-nums">{formatNum(c.tokens)}</TD>
                  <TD>
                    <Badge tone={conversionStatusTone[c.status]}>
                      {conversionStatusLabel[c.status]}
                    </Badge>
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
