'use client'

import { useState } from 'react'
import { Button } from '../ui/button'
import { Badge } from '../ui/badge'
import { Card } from '../ui/card'
import { Input, Textarea, Select, FormField } from '../ui/field'
import { Progress } from '../ui/progress'
import { Skeleton } from '../ui/skeleton'
import { Tabs, TabPanel } from '../ui/tabs'
import { Tooltip } from '../ui/tooltip'
import { IconButton } from '../ui/icon-button'
import { StatusMessage, EmptyState, ErrorState } from '../ui/feedback'
import { Dialog } from '../ui/dialog'
import { useToast } from '../ui/toast'
import { Table, THead, TBody, TR, TH, TD } from '../ui/table'
import { Bell, Trash2, Download, FileText } from 'lucide-react'

function Section({
  id,
  title,
  description,
  children,
}: {
  id: string
  title: string
  description?: string
  children: React.ReactNode
}) {
  return (
    <section id={id} aria-labelledby={`${id}-h`} className="scroll-mt-24">
      <div className="mb-5 border-b border-border pb-3">
        <h2 id={`${id}-h`} className="text-xl font-bold text-foreground">
          {title}
        </h2>
        {description && (
          <p className="mt-1 text-[15px] leading-relaxed text-muted-foreground">{description}</p>
        )}
      </div>
      {children}
    </section>
  )
}

const swatches = [
  { name: 'Primary', varName: '--primary', className: 'bg-primary' },
  { name: 'Foreground', varName: '--foreground', className: 'bg-foreground' },
  { name: 'Muted', varName: '--muted-foreground', className: 'bg-muted-foreground' },
  { name: 'Success', varName: '--success', className: 'bg-success' },
  { name: 'Warning', varName: '--warning', className: 'bg-warning' },
  { name: 'Danger', varName: '--danger', className: 'bg-danger' },
  { name: 'Info', varName: '--info', className: 'bg-info' },
]

export function Showcase() {
  const [tab, setTab] = useState('overview')
  const [dialogOpen, setDialogOpen] = useState(false)
  const { notify } = useToast()

  return (
    <div className="space-y-14">
      <Section
        id="colors"
        title="색상 팔레트"
        description="브랜드 색상은 신뢰감을 주는 청록(teal) 계열을 기본으로 하며, 상태 색상은 명도 대비 4.5:1 이상을 충족합니다."
      >
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-7">
          {swatches.map((s) => (
            <div key={s.name} className="flex flex-col gap-2">
              <div className={`h-20 rounded-[12px] border border-border ${s.className}`} />
              <div>
                <p className="text-sm font-semibold text-foreground">{s.name}</p>
                <p className="font-mono text-xs text-muted-foreground">{s.varName}</p>
              </div>
            </div>
          ))}
        </div>
      </Section>

      <Section
        id="typography"
        title="타이포그래피"
        description="본문은 최소 15px, 행간 1.6을 유지하여 저시력 사용자와 고령층의 가독성을 확보합니다."
      >
        <div className="space-y-4">
          <div className="flex items-baseline gap-4 border-b border-border pb-3">
            <span className="w-24 shrink-0 font-mono text-xs text-muted-foreground">H1 / 32</span>
            <p className="text-[32px] font-bold leading-tight text-foreground">쉬운 우리말 서비스</p>
          </div>
          <div className="flex items-baseline gap-4 border-b border-border pb-3">
            <span className="w-24 shrink-0 font-mono text-xs text-muted-foreground">H2 / 24</span>
            <p className="text-2xl font-bold text-foreground">행정 문서를 쉽게</p>
          </div>
          <div className="flex items-baseline gap-4 border-b border-border pb-3">
            <span className="w-24 shrink-0 font-mono text-xs text-muted-foreground">Body / 16</span>
            <p className="text-base leading-relaxed text-foreground">
              누구나 이해할 수 있는 공공언어로 바꿔 드립니다.
            </p>
          </div>
          <div className="flex items-baseline gap-4">
            <span className="w-24 shrink-0 font-mono text-xs text-muted-foreground">Caption / 14</span>
            <p className="text-sm text-muted-foreground">부가 설명 및 도움말 텍스트입니다.</p>
          </div>
        </div>
      </Section>

      <Section id="buttons" title="버튼" description="키보드 포커스 링과 44px 이상의 터치 타깃을 보장합니다.">
        <div className="flex flex-wrap items-center gap-3">
          <Button>기본 버튼</Button>
          <Button variant="secondary">보조</Button>
          <Button variant="outline">외곽선</Button>
          <Button variant="ghost">고스트</Button>
          <Button variant="danger">위험</Button>
          <Button disabled>비활성</Button>
          <Button size="sm">작게</Button>
          <Button size="lg">크게</Button>
          <Button>
            <FileText className="size-4" aria-hidden="true" />
            아이콘 포함
          </Button>
        </div>
      </Section>

      <Section id="badges" title="배지 · 상태 표시" description="상태는 색상뿐 아니라 텍스트로도 구분되어 색각 이상 사용자를 배려합니다.">
        <div className="flex flex-wrap gap-2">
          <Badge tone="teal">진행 중</Badge>
          <Badge tone="green">완료</Badge>
          <Badge tone="amber">검토 필요</Badge>
          <Badge tone="red">실패</Badge>
          <Badge tone="neutral">대기</Badge>
        </div>
      </Section>

      <Section id="forms" title="폼 요소" description="모든 입력에는 명시적 레이블과 도움말·오류 메시지가 연결됩니다.">
        <div className="grid gap-5 sm:grid-cols-2">
          <FormField label="기관명" htmlFor="demo-org" hint="사업자 등록 기준 정식 명칭">
            <Input id="demo-org" placeholder="예: 성남시청" />
          </FormField>
          <FormField label="담당자 이메일" htmlFor="demo-email" error="올바른 이메일 형식이 아닙니다.">
            <Input id="demo-email" type="email" defaultValue="invalid@" aria-invalid />
          </FormField>
          <FormField label="문서 유형" htmlFor="demo-type">
            <Select id="demo-type">
              <option>안내문</option>
              <option>보도자료</option>
              <option>공고문</option>
            </Select>
          </FormField>
          <FormField label="요청 사항" htmlFor="demo-note" className="sm:col-span-2">
            <Textarea id="demo-note" rows={3} placeholder="변환 시 유의할 점을 적어 주세요." />
          </FormField>
        </div>
      </Section>

      <Section id="feedback" title="피드백 · 상태" description="로딩, 빈 상태, 오류, 알림까지 일관된 패턴을 제공합니다.">
        <div className="grid gap-4 lg:grid-cols-2">
          <StatusMessage tone="success" title="변환이 완료되었습니다">
            결과를 검토한 뒤 발행할 수 있습니다.
          </StatusMessage>
          <StatusMessage tone="warning" title="토큰이 얼마 남지 않았습니다">
            잔여 토큰이 10% 미만입니다.
          </StatusMessage>
          <StatusMessage tone="danger" title="변환에 실패했습니다">
            문서 형식을 확인한 뒤 다시 시도해 주세요.
          </StatusMessage>
          <StatusMessage tone="info" title="검토 단계 안내">
            변환 결과는 담당자 검토 후 반영됩니다.
          </StatusMessage>
        </div>

        <div className="mt-6 grid gap-4 lg:grid-cols-2">
          <EmptyState
            title="아직 변환 내역이 없습니다"
            description="첫 문서를 업로드하면 이곳에 변환 기록이 표시됩니다."
            action={<Button size="sm">문서 변환하기</Button>}
          />
          <ErrorState onRetry={() => notify({ tone: 'info', title: '다시 시도하는 중입니다' })} />
        </div>

        <div className="mt-6 space-y-3">
          <p className="text-sm font-semibold text-foreground">스켈레톤 로딩</p>
          <Card>
            <div className="space-y-3">
              <Skeleton className="h-5 w-1/3" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-5/6" />
            </div>
          </Card>
        </div>
      </Section>

      <Section id="progress" title="진행 표시">
        <div className="max-w-md space-y-5">
          <Progress value={72} label="이번 달 토큰 사용량" showValue />
          <Progress value={40} tone="success" label="변환 진행률" showValue />
          <Progress value={88} tone="warning" label="저장 공간" showValue />
        </div>
      </Section>

      <Section id="overlays" title="오버레이 · 알림" description="모달은 포커스 트랩과 ESC 닫기를 지원하며, 토스트는 스크린리더에 안내됩니다.">
        <div className="flex flex-wrap gap-3">
          <Button variant="outline" onClick={() => setDialogOpen(true)}>
            모달 열기
          </Button>
          <Button
            variant="outline"
            onClick={() => notify({ tone: 'success', title: '저장되었습니다', description: '변경 사항이 반영되었습니다.' })}
          >
            토스트 표시
          </Button>
          <Tooltip label="새 알림 3건">
            <IconButton label="알림">
              <Bell className="size-5" aria-hidden="true" />
            </IconButton>
          </Tooltip>
          <IconButton label="다운로드">
            <Download className="size-5" aria-hidden="true" />
          </IconButton>
          <IconButton label="삭제">
            <Trash2 className="size-5" aria-hidden="true" />
          </IconButton>
        </div>

        <Dialog
          open={dialogOpen}
          onClose={() => setDialogOpen(false)}
          title="변환 결과 발행"
          description="검토가 완료된 문서를 발행하시겠습니까? 발행 후에도 내역에서 다시 확인할 수 있습니다."
          footer={
            <>
              <Button variant="ghost" onClick={() => setDialogOpen(false)}>
                취소
              </Button>
              <Button
                onClick={() => {
                  setDialogOpen(false)
                  notify({ tone: 'success', title: '문서가 발행되었습니다' })
                }}
              >
                발행하기
              </Button>
            </>
          }
        >
          <p className="text-[15px] leading-relaxed text-muted-foreground">
            발행 시 담당 부서로 알림이 전송됩니다.
          </p>
        </Dialog>
      </Section>

      <Section id="tabs" title="탭" description="좌우 화살표 키로 이동할 수 있는 접근성 탭입니다.">
        <Tabs
          idBase="demo"
          value={tab}
          onValueChange={setTab}
          items={[
            { value: 'overview', label: '개요' },
            { value: 'usage', label: '사용법' },
            { value: 'policy', label: '정책' },
          ]}
        />
        <div className="pt-4">
          <TabPanel idBase="demo" value="overview" active={tab === 'overview'}>
            <p className="text-[15px] leading-relaxed text-foreground">개요 탭의 내용입니다.</p>
          </TabPanel>
          <TabPanel idBase="demo" value="usage" active={tab === 'usage'}>
            <p className="text-[15px] leading-relaxed text-foreground">사용법 탭의 내용입니다.</p>
          </TabPanel>
          <TabPanel idBase="demo" value="policy" active={tab === 'policy'}>
            <p className="text-[15px] leading-relaxed text-foreground">정책 탭의 내용입니다.</p>
          </TabPanel>
        </div>
      </Section>

      <Section id="table" title="테이블" description="정렬 가능한 헤더와 시맨틱 마크업을 갖춘 데이터 테이블입니다.">
        <Card className="overflow-hidden p-0">
          <Table>
            <THead>
              <TR>
                <TH>문서 ID</TH>
                <TH>유형</TH>
                <TH>상태</TH>
                <TH className="text-right">토큰</TH>
              </TR>
            </THead>
            <TBody>
              <TR>
                <TD className="font-mono text-[13px] text-muted-foreground">CV-0416</TD>
                <TD>안내문</TD>
                <TD>
                  <Badge tone="green">완료</Badge>
                </TD>
                <TD className="text-right tabular-nums">1,240</TD>
              </TR>
              <TR>
                <TD className="font-mono text-[13px] text-muted-foreground">CV-0417</TD>
                <TD>공고문</TD>
                <TD>
                  <Badge tone="teal">진행 중</Badge>
                </TD>
                <TD className="text-right tabular-nums">860</TD>
              </TR>
            </TBody>
          </Table>
        </Card>
      </Section>
    </div>
  )
}
