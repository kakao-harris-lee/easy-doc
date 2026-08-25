'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Button } from '../../ui/button'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { notices } from '../../lib/mock-data'
import { Plus } from 'lucide-react'

const statusTone: Record<string, 'success' | 'info' | 'neutral'> = {
  '게시 중': 'success',
  예약: 'info',
  초안: 'neutral',
}

export function AdminNotices() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="공지 관리"
        description="기관과 운영자에게 전달할 공지를 관리합니다."
        actions={
          <Button size="sm">
            <Plus className="size-4" aria-hidden="true" />
            공지 작성
          </Button>
        }
      />

      <Card>
        <CardContent className="p-0">
          <Table caption="공지 목록">
            <THead>
              <TR>
                <TH>공지 ID</TH>
                <TH>제목</TH>
                <TH>대상</TH>
                <TH>최근 수정</TH>
                <TH>상태</TH>
              </TR>
            </THead>
            <TBody>
              {notices.map((n) => (
                <TR key={n.id}>
                  <TD className="font-mono text-sm text-muted-foreground">{n.id}</TD>
                  <TD className="max-w-md font-semibold">
                    <span className="line-clamp-1">{n.title}</span>
                  </TD>
                  <TD>
                    <Badge tone="neutral" withIcon={false}>
                      {n.audience}
                    </Badge>
                  </TD>
                  <TD className="tabular-nums text-muted-foreground">{n.updatedAt}</TD>
                  <TD>
                    <Badge tone={statusTone[n.status]}>{n.status}</Badge>
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
