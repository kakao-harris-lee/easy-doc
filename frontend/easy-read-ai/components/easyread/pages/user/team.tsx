'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../../ui/card'
import { Button } from '../../ui/button'
import { Badge } from '../../ui/badge'
import { FormField, Input, Select } from '../../ui/field'
import { Table, THead, TBody, TR, TH, TD } from '../../ui/table'
import { Dialog } from '../../ui/dialog'
import { IconButton } from '../../ui/icon-button'
import { useToast } from '../../ui/toast'
import { UserPlus, Trash2, MailCheck } from 'lucide-react'
import { members as seedMembers, type Member } from '../../lib/mock-data'

const roleTone: Record<Member['role'], 'primary' | 'info' | 'neutral'> = {
  관리자: 'primary',
  편집자: 'info',
  검토자: 'neutral',
}
const statusTone: Record<Member['status'], 'success' | 'warning' | 'neutral'> = {
  활성: 'success',
  초대됨: 'warning',
  비활성: 'neutral',
}

export function TeamPage() {
  const { notify } = useToast()
  const [members, setMembers] = React.useState<Member[]>(seedMembers)
  const [inviteOpen, setInviteOpen] = React.useState(false)
  const [removeTarget, setRemoveTarget] = React.useState<Member | null>(null)

  const [name, setName] = React.useState('')
  const [email, setEmail] = React.useState('')
  const [role, setRole] = React.useState<Member['role']>('편집자')
  const [errors, setErrors] = React.useState<{ name?: string; email?: string }>({})

  function resetForm() {
    setName('')
    setEmail('')
    setRole('편집자')
    setErrors({})
  }

  function submitInvite() {
    const next: typeof errors = {}
    if (name.trim().length < 2) next.name = '이름을 2자 이상 입력해 주세요.'
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
      next.email = '올바른 이메일 주소를 입력해 주세요.'
    setErrors(next)
    if (Object.keys(next).length > 0) return

    setMembers((prev) => [
      ...prev,
      {
        id: `U${prev.length + 1}${Date.now() % 100}`,
        name: name.trim(),
        email: email.trim(),
        role,
        status: '초대됨',
      },
    ])
    setInviteOpen(false)
    resetForm()
    notify({
      tone: 'success',
      title: '초대장을 보냈습니다',
      description: `${email} 주소로 초대 메일을 발송했습니다.`,
    })
  }

  function confirmRemove() {
    if (!removeTarget) return
    setMembers((prev) => prev.filter((m) => m.id !== removeTarget.id))
    notify({ tone: 'info', title: `${removeTarget.name} 님을 팀에서 제외했습니다` })
    setRemoveTarget(null)
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="팀 관리"
        description="기관 구성원을 초대하고 역할을 관리합니다."
        actions={
          <Button onClick={() => setInviteOpen(true)}>
            <UserPlus className="size-4" aria-hidden="true" />
            구성원 초대
          </Button>
        }
      />

      <Card>
        <CardHeader>
          <CardTitle as="h2">구성원 {members.length}명</CardTitle>
          <CardDescription>
            역할에 따라 변환·검토·발행 권한이 달라집니다.
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0 py-0">
          <Table caption="팀 구성원 목록">
            <THead>
              <TR>
                <TH>이름</TH>
                <TH>이메일</TH>
                <TH>역할</TH>
                <TH>상태</TH>
                <TH><span className="sr-only">동작</span></TH>
              </TR>
            </THead>
            <TBody>
              {members.map((m) => (
                <TR key={m.id}>
                  <TD className="font-semibold text-foreground">{m.name}</TD>
                  <TD className="text-muted-foreground">{m.email}</TD>
                  <TD><Badge tone={roleTone[m.role]} withIcon={false}>{m.role}</Badge></TD>
                  <TD><Badge tone={statusTone[m.status]}>{m.status}</Badge></TD>
                  <TD>
                    <IconButton
                      label={`${m.name} 제외`}
                      size="sm"
                      onClick={() => setRemoveTarget(m)}
                    >
                      <Trash2 className="size-5" aria-hidden="true" />
                    </IconButton>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        </CardContent>
      </Card>

      {/* Invite dialog */}
      <Dialog
        open={inviteOpen}
        onClose={() => { setInviteOpen(false); resetForm() }}
        title="구성원 초대"
        description="초대할 사람의 정보를 입력하면 이메일로 초대장을 보냅니다."
        footer={
          <>
            <Button variant="ghost" onClick={() => { setInviteOpen(false); resetForm() }}>
              취소
            </Button>
            <Button onClick={submitInvite}>
              <MailCheck className="size-4" aria-hidden="true" />
              초대장 보내기
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <FormField id="invite-name" label="이름" error={errors.name} required>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="홍길동" />
          </FormField>
          <FormField id="invite-email" label="이메일" error={errors.email} required>
            <Input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@welfare.go.kr"
            />
          </FormField>
          <FormField id="invite-role" label="역할" hint="검토자는 발행 전 검토만, 편집자는 변환과 수정이 가능합니다.">
            <Select value={role} onChange={(e) => setRole(e.target.value as Member['role'])}>
              <option value="편집자">편집자</option>
              <option value="검토자">검토자</option>
              <option value="관리자">관리자</option>
            </Select>
          </FormField>
        </div>
      </Dialog>

      {/* Remove confirm */}
      <Dialog
        open={removeTarget !== null}
        onClose={() => setRemoveTarget(null)}
        size="sm"
        title="구성원을 제외할까요?"
        description={removeTarget ? `${removeTarget.name} (${removeTarget.email})` : ''}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoveTarget(null)}>취소</Button>
            <Button variant="danger" onClick={confirmRemove}>
              <Trash2 className="size-4" aria-hidden="true" />
              제외하기
            </Button>
          </>
        }
      >
        <p className="text-[15px] leading-relaxed text-muted-foreground">
          제외된 구성원은 더 이상 이 기관의 문서에 접근할 수 없습니다. 필요하면
          다시 초대할 수 있습니다.
        </p>
      </Dialog>
    </div>
  )
}
