'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent } from '../../ui/card'
import { Badge } from '../../ui/badge'
import { Button } from '../../ui/button'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { Dialog } from '../../ui/dialog'
import { FormField, Input, Select, Textarea } from '../../ui/field'
import { useToast } from '../../ui/toast'
import { tokenAdjustments, customers, formatNum } from '../../lib/mock-data'
import { Plus, ArrowUpRight, ArrowDownRight } from 'lucide-react'

export function AdminAdjustments() {
  const { notify } = useToast()
  const [open, setOpen] = React.useState(false)
  const [org, setOrg] = React.useState('')
  const [amount, setAmount] = React.useState('')
  const [reason, setReason] = React.useState('')
  const [errors, setErrors] = React.useState<{ org?: string; amount?: string; reason?: string }>({})

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const next: typeof errors = {}
    if (!org) next.org = '기관을 선택해 주세요.'
    if (!amount || Number.isNaN(Number(amount)) || Number(amount) === 0)
      next.amount = '0이 아닌 숫자를 입력해 주세요.'
    if (!reason.trim()) next.reason = '조정 사유를 입력해 주세요.'
    setErrors(next)
    if (Object.keys(next).length > 0) return
    setOpen(false)
    setOrg('')
    setAmount('')
    setReason('')
    setErrors({})
    notify({
      tone: 'success',
      title: '토큰이 조정되었습니다',
      description: `${org}에 ${formatNum(Number(amount))} 토큰이 반영되었습니다.`,
    })
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="토큰 조정"
        description="기관별 토큰을 지급하거나 회수한 이력입니다."
        actions={
          <Button size="sm" onClick={() => setOpen(true)}>
            <Plus className="size-4" aria-hidden="true" />
            토큰 조정
          </Button>
        }
      />

      <Card>
        <CardContent className="p-0">
          <Table caption="토큰 조정 이력">
            <THead>
              <TR>
                <TH>조정 ID</TH>
                <TH>기관</TH>
                <TH className="text-right">변동</TH>
                <TH>사유</TH>
                <TH>담당자</TH>
                <TH>일시</TH>
              </TR>
            </THead>
            <TBody>
              {tokenAdjustments.map((a) => {
                const grant = a.amount >= 0
                return (
                  <TR key={a.id}>
                    <TD className="font-mono text-sm text-muted-foreground">{a.id}</TD>
                    <TD className="font-semibold">{a.org}</TD>
                    <TD className="text-right">
                      <Badge tone={grant ? 'success' : 'danger'} withIcon={false}>
                        {grant ? (
                          <ArrowUpRight className="size-3.5" aria-hidden="true" />
                        ) : (
                          <ArrowDownRight className="size-3.5" aria-hidden="true" />
                        )}
                        <span className="tabular-nums">
                          {grant ? '+' : ''}
                          {formatNum(a.amount)}
                        </span>
                      </Badge>
                    </TD>
                    <TD className="text-muted-foreground">{a.reason}</TD>
                    <TD className="text-muted-foreground">{a.operator}</TD>
                    <TD className="tabular-nums text-muted-foreground">{a.date}</TD>
                  </TR>
                )
              })}
            </TBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={open} onClose={() => setOpen(false)} title="토큰 조정" description="기관에 토큰을 지급하거나 회수합니다. 양수는 지급, 음수는 회수입니다.">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <FormField id="adj-org" label="기관" error={errors.org} required>
            <Select value={org} onChange={(e) => setOrg(e.target.value)}>
              <option value="">기관 선택</option>
              {customers.map((c) => (
                <option key={c.id} value={c.org}>
                  {c.org}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField
            id="adj-amount"
            label="변동 토큰 수"
            hint="지급은 양수(예: 50000), 회수는 음수(예: -8000)로 입력합니다."
            error={errors.amount}
            required
          >
            <Input
              type="number"
              inputMode="numeric"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="50000"
            />
          </FormField>
          <FormField id="adj-reason" label="조정 사유" error={errors.reason} required>
            <Textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="예: 변환 실패 보상"
              className="min-h-24"
            />
          </FormField>
          <div className="mt-1 flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              취소
            </Button>
            <Button type="submit">조정 적용</Button>
          </div>
        </form>
      </Dialog>
    </div>
  )
}
