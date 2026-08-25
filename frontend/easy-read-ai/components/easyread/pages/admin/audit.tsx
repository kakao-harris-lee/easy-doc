'use client'

import { useState } from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { auditLog } from '../../lib/mock-data'
import { Search } from 'lucide-react'

const actionTone: Record<string, 'teal' | 'amber' | 'red' | 'neutral'> = {
  '토큰 지급': 'teal',
  '구독 정지': 'red',
  '문서 발행': 'neutral',
  '공지 게시': 'amber',
  로그인: 'neutral',
}

export function AdminAudit() {
  const [q, setQ] = useState('')
  const rows = auditLog.filter(
    (l) =>
      l.actor.toLowerCase().includes(q.toLowerCase()) ||
      l.action.toLowerCase().includes(q.toLowerCase()) ||
      l.target.toLowerCase().includes(q.toLowerCase()),
  )

  return (
    <div className="space-y-6">
      <PageHeader
        title="감사 로그"
        description="운영자와 사용자의 주요 행위가 시간순으로 기록됩니다. 규정 준수 및 사고 대응에 활용하세요."
      />

      <div className="max-w-sm">
        <label htmlFor="audit-search" className="sr-only">
          로그 검색
        </label>
        <div className="relative">
          <Search
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <input
            id="audit-search"
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="행위자, 작업, 대상 검색"
            className="h-11 w-full rounded-[10px] border border-input bg-card pl-9 pr-3 text-[15px] text-foreground outline-none placeholder:text-muted-foreground focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
          />
        </div>
      </div>

      <Card className="overflow-hidden p-0">
        <Table>
          <THead>
            <TR>
              <TH>로그 ID</TH>
              <TH>행위자</TH>
              <TH>작업</TH>
              <TH>대상</TH>
              <TH>IP 주소</TH>
              <TH>시각</TH>
            </TR>
          </THead>
          <TBody>
            {rows.map((l) => (
              <TR key={l.id}>
                <TD className="font-mono text-[13px] text-muted-foreground">{l.id}</TD>
                <TD className="font-medium text-foreground">{l.actor}</TD>
                <TD>
                  <Badge tone={actionTone[l.action] ?? 'neutral'}>{l.action}</Badge>
                </TD>
                <TD className="text-muted-foreground">{l.target}</TD>
                <TD className="font-mono text-[13px] text-muted-foreground">{l.ip}</TD>
                <TD className="whitespace-nowrap font-mono text-[13px] text-muted-foreground">{l.at}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </Card>
    </div>
  )
}
